package com.lcl.myaiagent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件工具的路径守卫（D1 收口，2026-09-15 复核）。
 * <p>
 * 背景：{@code fileName} 由模型给出，而模型的输入（用户提示词）由调用方控制；未加校验时
 * {@code ../x} 会逃出 {@code tmp/file} 目录，形成**任意文件读写**。本类钉住"越界一律拒、
 * 正常名照常放行"。实测（负向控制，摘掉守卫）：{@code writeFile("../../Windows/win.ini")}
 * 真的在仓库根下创建了 {@code Windows/win.ini} 并写入内容——本类即是对这条的回归锁。
 * </p>
 * <p>
 * 三条刻意的设计约束（都是为了让"跑测试"本身不产生副作用）：
 * <ul>
 *   <li><b>零持久 I/O</b>：放行一侧不去真写文件，用"错误信息里不含越界标记"来断言；</li>
 *   <li><b>不碰绝对路径的写</b>：绝对路径只验读（读无害）；要验"绝对路径的写也被拒"时，
 *       用一个位于系统临时目录下的路径——万一守卫被摘掉，副作用也只是临时目录里一个文件；</li>
 *   <li><b>相对穿越的写用落点在 tmp/ 的名字</b>：万一守卫被摘掉，产物落在
 *       {@code <user.dir>/tmp/}（已 gitignore），不会弄脏工作区。</li>
 * </ul>
 * 本类不是 {@code @SpringBootTest}（不需要 MySQL/Redis），因此它在本仓门禁里真的会跑，
 * 不像 {@code FileOperationToolTest} 那样被排除。
 * </p>
 */
@DisplayName("文件工具的路径守卫")
class FileOperationToolPathGuardTest {

    private final FileOperationTool tool = new FileOperationTool();

    /**
     * 删除一个由本测试自己构造的路径，忽略失败。
     * 它只用于清掉"上一次运行（尤其是摘掉守卫的负向控制）可能留下的痕迹"，清理失败不影响判定 ——
     * 故刻意不把 IOException 上抛成用例失败。
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 清理失败不影响判定
        }
    }

    /** 相对穿越：读与写都必须被拒 */
    private void assertRelativeTraversalRejected(String name) {
        String readResult = tool.readFile(name);
        String writeResult = tool.writeFile(name, "should never be written");
        assertTrue(readResult.startsWith(FileOperationTool.ILLEGAL_NAME_PREFIX),
                "readFile 应拒绝越界文件名 <" + name + ">，实际返回：" + readResult);
        assertTrue(writeResult.startsWith(FileOperationTool.ILLEGAL_NAME_PREFIX),
                "writeFile 应拒绝越界文件名 <" + name + ">，实际返回：" + writeResult);
    }

    /** 绝对路径：只验读，绝不对真实的绝对路径执行写 */
    private void assertAbsolutePathReadRejected(String name) {
        String readResult = tool.readFile(name);
        assertTrue(readResult.startsWith(FileOperationTool.ILLEGAL_NAME_PREFIX),
                "readFile 应拒绝绝对路径 <" + name + ">，实际返回：" + readResult);
    }

    /** 放行一侧的断言：不能以拒答前缀开头（文件不存在时返回的是"读失败"，那是另一回事） */
    private void assertLetThrough(String name) {
        String result = tool.readFile(name);
        assertFalse(result.startsWith(FileOperationTool.ILLEGAL_NAME_PREFIX),
                "readFile 不应拒绝正常文件名 <" + name + ">，实际返回：" + result);
    }

    @Test
    @DisplayName("相对穿越 ../ 被拒")
    void rejectsParentTraversal() {
        assertRelativeTraversalRejected("../outside.txt");
        assertRelativeTraversalRejected("../../outside.txt");
    }

    @Test
    @DisplayName("多级穿越与伪装成子路径的穿越被拒")
    void rejectsDeeperTraversal() {
        // 先进入子目录再穿出去：归一化之后仍然在工具目录外
        assertRelativeTraversalRejected("sub/../../outside.txt");
        assertRelativeTraversalRejected("./../outside.txt");
        assertRelativeTraversalRejected("../subdir/../../outside.txt");
    }

    @Test
    @DisplayName("绝对路径的读被拒（Unix 与 Windows 两种形态）")
    void rejectsAbsolutePathReads() {
        assertAbsolutePathReadRejected("/etc/passwd");
        assertAbsolutePathReadRejected("C:\\Windows\\win.ini");
    }

    @Test
    @DisplayName("绝对路径的写也被拒（用系统临时目录验，摘掉守卫的副作用最小）")
    void rejectsAbsolutePathWrites() {
        Path abs = Paths.get(System.getProperty("java.io.tmpdir"), "d1-guard-test-should-not-exist.txt");
        try {
            deleteQuietly(abs);
            String result = tool.writeFile(abs.toString(), "should never be written");
            assertTrue(result.startsWith(FileOperationTool.ILLEGAL_NAME_PREFIX),
                    "writeFile 应拒绝绝对路径 <" + abs + ">，实际返回：" + result);
            assertTrue(Files.notExists(abs), "绝对路径的写不得落到文件系统：" + abs);
        } finally {
            // 失败也不留残骸；删除的路径由本用例自己构造，不指向任何真实数据
            deleteQuietly(abs);
        }
    }

    @Test
    @DisplayName("空名与 null 被拒")
    void rejectsBlankNames() {
        assertRelativeTraversalRejected("");
        assertRelativeTraversalRejected("   ");
        assertRelativeTraversalRejected(null);
    }

    @Test
    @DisplayName("穿越的写入不会真的写到工具目录之外")
    void traversalWriteLeavesNoFileOutside() {
        // 工具目录是 <user.dir>/tmp/file，故 ../<marker> 的落点是 <user.dir>/tmp/<marker>（已被 gitignore）
        Path escapeTarget = Paths.get(System.getProperty("user.dir"), "tmp", "guard-test-should-not-exist.txt");
        try {
            deleteQuietly(escapeTarget); // 清掉上一次可能失败的运行留下的痕迹，保证判定只反映当前代码
            tool.writeFile("../guard-test-should-not-exist.txt", "should never be written");
            assertTrue(Files.notExists(escapeTarget),
                    "越界写入不得落到工具目录之外，但发现了文件：" + escapeTarget);
        } finally {
            deleteQuietly(escapeTarget);
        }
    }

    @Test
    @DisplayName("正常文件名与子目录名照常放行")
    void allowsNormalNames() {
        assertLetThrough("no-such-file-for-guard-test.txt");
        assertLetThrough("subdir/no-such-file-for-guard-test.txt");
    }
}
