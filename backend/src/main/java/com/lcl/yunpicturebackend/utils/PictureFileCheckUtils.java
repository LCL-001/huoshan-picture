package com.lcl.yunpicturebackend.utils;

import java.io.IOException;
import java.io.InputStream;

/**
 * 图片文件魔数校验工具
 * 根据文件头判断真实内容是否为受支持的图片格式，防止伪造扩展名上传任意文件
 */
public class PictureFileCheckUtils {

    private PictureFileCheckUtils() {
    }

    /**
     * 魔数判定的读取长度（WEBP 需要前 12 字节：RIFF + 4 字节长度 + WEBP）
     */
    public static final int HEADER_LENGTH = 12;

    private static final byte[] JPEG_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF_MAGIC = new byte[]{0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_MAGIC = new byte[]{0x57, 0x45, 0x42, 0x50};

    /**
     * 从输入流读取文件头，自动处理短读；调用方负责关闭流
     */
    public static byte[] readHeader(InputStream in, int length) throws IOException {
        byte[] header = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(header, off, length - off);
            if (n < 0) {
                // 文件比魔数还短，直接返回已读部分交由魔数比对失败
                byte[] partial = new byte[off];
                System.arraycopy(header, 0, partial, 0, off);
                return partial;
            }
            off += n;
        }
        return header;
    }

    /**
     * 判断文件头是否为受支持的图片格式（jpg/jpeg/png/webp）
     */
    public static boolean isSupportedImage(byte[] header) {
        return header != null
                && (startsWith(header, JPEG_MAGIC) || startsWith(header, PNG_MAGIC)
                || (startsWith(header, RIFF_MAGIC) && header.length >= 12 && matchesAt(header, WEBP_MAGIC, 8)));
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        return matchesAt(data, prefix, 0);
    }

    private static boolean matchesAt(byte[] data, byte[] prefix, int offset) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
