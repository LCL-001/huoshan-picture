package com.lcl.yunpicturebackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lcl.yunpicturebackend.exception.BusinessException;
import com.lcl.yunpicturebackend.exception.ErrorCode;
import com.lcl.yunpicturebackend.exception.ThrowUtils;
import com.lcl.yunpicturebackend.utils.PictureFileCheckUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * URL 图片上传
 * <p>
 * SSRF 防护：
 * 1. 禁用自动重定向，手动跟随（≤3 跳），每一跳重新解析 DNS 并执行内网黑名单校验；
 * 2. 黑名单覆盖环回、私网、链路本地（云 metadata）、0.0.0.0/8、CGNAT(100.64.0.0/10，
 *    含 100.100.100.200)、IPv4-mapped IPv6、多播、保留段等；
 * 3. http 直连已校验的 IP 并保留原始 Host 头（依赖 sun.net.http.allowRestrictedHeaders=true，主类已开启），
 *    消除"校验与连接分离"导致的 DNS rebinding 窗口；https 因 SNI 与证书校验必须按域名连接，
 *    依靠逐跳校验兜底（校验与连接间隔为毫秒级）；
 * 4. 网络阶段失败统一返回"上传失败"，不区分拦截原因，避免错误文案成为内网探测 oracle；
 * 5. 下载内容做魔数校验，远端缺失 Content-Type 时同样能拦截伪装文件。
 */
@Slf4j
@Service
public class URLFilePictureUpload extends PictureUploadTemplate {

    /**
     * 允许下载的最大文件大小：2MB
     */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /**
     * 最大重定向次数
     */
    private static final int MAX_REDIRECTS = 3;

    /**
     * 网络阶段统一失败文案（与模板层下载失败文案一致，消除探测 oracle）
     */
    private static final String FETCH_ERROR = "上传失败";

    /**
     * 图片存储后缀白名单：URL 路径后缀不可信，不在白名单内的一律按 jpg 存储
     */
    private static final List<String> ALLOW_SUFFIX_LIST = Arrays.asList("jpg", "jpeg", "png", "webp");

    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        String fileURL = (String) inputSource;
        // 手动跟随重定向并逐跳校验后下载，流式限制大小，防止 Content-Length 被伪造时下载超大文件
        HttpURLConnection conn = fetch(new URL(fileURL), "GET");
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw fetchError();
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(file.toPath())) {
                // 魔数校验：文件内容必须是真实图片，防止伪造 Content-Type/扩展名
                byte[] header = PictureFileCheckUtils.readHeader(in, PictureFileCheckUtils.HEADER_LENGTH);
                if (!PictureFileCheckUtils.isSupportedImage(header)) {
                    throw fetchError();
                }
                out.write(header, 0, header.length);
                long total = (long) header.length;
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    total += bytesRead;
                    if (total > MAX_FILE_BYTES) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件大小不能超过 2M");
                    }
                    out.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    protected String getOriginalFilename(Object inputSource) {
        String fileURL = (String) inputSource;
        String fileName;
        try {
            URL url = new URL(fileURL);
            fileName = FileUtil.getName(url.getPath());
        } catch (MalformedURLException e) {
            fileName = null;
        }
        if (StrUtil.isBlank(fileName)) {
            fileName = "image.jpg";
        }
        // 路径后缀不在白名单内（如 .html/.svg）时按 jpg 存储，避免不可信后缀落入对象存储 key
        String suffix = FileUtil.getSuffix(fileName);
        if (suffix == null || !ALLOW_SUFFIX_LIST.contains(suffix.toLowerCase(Locale.ROOT))) {
            fileName = "image.jpg";
        }
        return fileName;
    }

    @Override
    protected void validPicture(Object inputSource) {
        String fileURL = (String) inputSource;
        // 1. 校验非空
        ThrowUtils.throwIf(StringUtils.isBlank(fileURL), ErrorCode.PARAMS_ERROR, "文件地址不能为空");
        // 2. 校验 URL 格式
        URL url;
        try {
            url = new URL(fileURL);
        } catch (MalformedURLException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件地址格式错误");
        }
        // 3. 校验 URL 协议
        ThrowUtils.throwIf(!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())
                        || StrUtil.isBlank(url.getHost()),
                ErrorCode.PARAMS_ERROR, "文件地址格式错误");
        // 4. HEAD 预检（手动跟随重定向、逐跳校验、直连校验过的地址），用于快速失败；
        //    非 200 不拦截，交给下载阶段的魔数与大小校验兜底（部分服务器不支持 HEAD）
        HttpURLConnection conn = fetch(url, "HEAD");
        try {
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                // 5. 校验文件大小（实际下载时还会做二次限制，此处仅快速失败）
                String contentLengthStr = conn.getHeaderField("Content-Length");
                if (StringUtils.isNotBlank(contentLengthStr)) {
                    try {
                        ThrowUtils.throwIf(Long.parseLong(contentLengthStr) > MAX_FILE_BYTES,
                                ErrorCode.PARAMS_ERROR, "文件大小不能超过 2M");
                    } catch (NumberFormatException ignore) {
                        // 非法 Content-Length 交给下载阶段限制
                    }
                }
            }
        } catch (IOException e) {
            throw fetchError();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 打开连接并手动跟随重定向：每一跳都重新解析 DNS、执行黑名单校验后直连
     */
    private HttpURLConnection fetch(URL initial, String method) {
        URL current = initial;
        for (int hop = 0; ; hop++) {
            if (hop > MAX_REDIRECTS) {
                throw fetchError();
            }
            // 每一跳重新解析 DNS 并校验黑名单，返回通过校验的地址用于直连
            InetAddress address = pickValidAddress(current.getHost());
            HttpURLConnection conn = null;
            try {
                conn = open(current, address, method);
                int status = conn.getResponseCode();
                if (!isRedirect(status)) {
                    return conn;
                }
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (StrUtil.isBlank(location)) {
                    throw fetchError();
                }
                URL next = absolutize(current, location);
                if (next == null) {
                    throw fetchError();
                }
                current = next;
            } catch (IOException e) {
                if (conn != null) {
                    conn.disconnect();
                }
                // 连接失败/超时等一律统一文案，不暴露内网探测细节
                throw fetchError();
            }
        }
    }

    /**
     * 建立连接：http 直连已校验的 IP 并保留原始 Host 头，https 按域名连接（SNI/证书校验需要）
     */
    private HttpURLConnection open(URL url, InetAddress pinned, String method) throws IOException {
        boolean isHttp = "http".equalsIgnoreCase(url.getProtocol());
        URL target = isHttp
                ? new URL("http", pinned.getHostAddress(), url.getPort(), url.getFile())
                : url;
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "yu-picture-url-uploader");
        if (isHttp) {
            conn.setRequestProperty("Host", hostHeader(url));
        }
        return conn;
    }

    /**
     * 解析主机的全部地址并执行黑名单校验，任一地址命中即拒绝；返回通过校验的首个地址
     */
    private InetAddress pickValidAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripBrackets(host));
        } catch (UnknownHostException e) {
            // 统一文案：不区分"无法解析"与"内网拦截"
            throw fetchError();
        }
        if (addresses == null || addresses.length == 0) {
            throw fetchError();
        }
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) {
                throw fetchError();
            }
        }
        return addresses[0];
    }

    /**
     * 内网/保留地址黑名单：任一解析结果命中即视为非法
     */
    private boolean isForbiddenAddress(InetAddress address) {
        // IPv4-mapped IPv6（如 ::ffff:127.0.0.1）还原为 IPv4 后判断，避免绕过
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            if (b.length == 16 && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0
                    && b[6] == 0 && b[7] == 0 && b[8] == 0 && b[9] == 0 && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
                byte[] v4 = new byte[]{b[12], b[13], b[14], b[15]};
                return isForbiddenIpv4(v4) || address.isMulticastAddress();
            }
            // IPv6 环回（::1）、未指定地址、链路本地（fe80::/10）、唯一本地（fc00::/7）、多播
            return address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocalIpv6(address);
        }
        return isForbiddenIpv4(address.getAddress());
    }

    private boolean isForbiddenIpv4(byte[] b) {
        if (b.length != 4) {
            return false;
        }
        // 0.0.0.0/8（"this network"，含 0.0.0.0）
        if (b[0] == 0) {
            return true;
        }
        // 10/8、172.16/12、192.168/16（私网）、127/8（环回）、169.254/16（链路本地，含云 metadata）
        if (b[0] == 10 || (b[0] & 0xFF) == 127 || b[0] == (byte) 0xAC && (b[1] & 0xF0) == 0x10
                || b[0] == (byte) 0xC0 && b[1] == (byte) 0xA8 || b[0] == (byte) 0xA9 && b[1] == (byte) 0xFE) {
            return true;
        }
        // 100.64.0.0/10（CGNAT，含阿里云/腾讯云 metadata 100.100.100.200）
        if (b[0] == 100 && (b[1] & 0xC0) == 0x40) {
            return true;
        }
        // 192.0.0.0/24、192.0.2.0/24、198.18.0.0/15、198.51.100.0/24、203.0.113.0/24（保留/基准/文档段）
        if (b[0] == (byte) 0xC0 && b[1] == 0 && b[2] == 0) {
            return true;
        }
        if (b[0] == (byte) 0xC0 && b[1] == 0 && b[2] == 2) {
            return true;
        }
        if (b[0] == (byte) 0xC6 && (b[1] == 18 || b[1] == 19)) {
            return true;
        }
        if (b[0] == (byte) 0xC6 && b[1] == 51 && b[2] == 100) {
            return true;
        }
        if (b[0] == (byte) 0xCB && b[1] == 0 && b[2] == 113) {
            return true;
        }
        // 224.0.0.0/4（多播）与 240.0.0.0/4（保留，含 255.255.255.255）
        return (b[0] & 0xF0) == 0xE0 || (b[0] & 0xF0) == 0xF0;
    }

    /**
     * IPv6 唯一本地地址（fc00::/7）
     */
    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] == (byte) 0xfc || bytes[0] == (byte) 0xfd);
    }

    /**
     * 重定向目标绝对化，且协议仍必须为 http/https
     */
    private URL absolutize(URL base, String location) {
        try {
            URL next = new URL(base, location);
            if (!"http".equalsIgnoreCase(next.getProtocol()) && !"https".equalsIgnoreCase(next.getProtocol())) {
                return null;
            }
            if (StrUtil.isBlank(next.getHost())) {
                return null;
            }
            return next;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == 303 || status == 307 || status == 308;
    }

    /**
     * 去掉 IPv6 字面量的方括号，供 InetAddress 解析
     */
    private String stripBrackets(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : host.substring(1);
        }
        return host;
    }

    /**
     * 原始 Host 头（直连 IP 时用于保持虚拟主机路由，IPv6 字面量按规范保留方括号）
     */
    private String hostHeader(URL url) {
        int port = url.getPort();
        boolean defaultPort = port == -1
                || ("http".equalsIgnoreCase(url.getProtocol()) && port == 80)
                || ("https".equalsIgnoreCase(url.getProtocol()) && port == 443);
        return defaultPort ? url.getHost() : url.getHost() + ":" + port;
    }

    private BusinessException fetchError() {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, FETCH_ERROR);
    }
}
