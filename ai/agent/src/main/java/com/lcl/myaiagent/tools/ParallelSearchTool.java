package com.lcl.myaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行检索工具（DeepResearchAgent 取证阶段专用）。
 * <p>
 * 动机：研究工作流的取证阶段天然要把 2-4 个检索维度一次性铺开。
 * 让模型逐条调 webSearch 的话，每条都要串行等一次完整 LLM 往返；
 * 这里把"一批维度"交给模型一次调用，工具内部并发执行，总耗时 ≈ 最慢一条。
 * </p>
 * <p>
 * 线程模型（三条硬约束）：
 * 1. 专用有界线程池，绝不用公共 ForkJoinPool——本项目踩过的坑：
 *    阻塞 IO 占满公共池会殃及全 JVM 的并行流与 CompletableFuture；
 * 2. 有界队列 + CallerRunsPolicy：队列满时提交线程自己执行，形成背压，永不拒绝任务；
 * 3. 守护线程：工具实例随 Spring 容器存活，池不需要显式关闭，
 *    守护线程保证停机不被进行中的搜索拖住。
 * </p>
 * <p>
 * 失败模型：单查询超时/失败只降级该查询（结果里给占位说明），
 * 其余查询照常返回——部分结果好过没有结果。
 * </p>
 */
public class ParallelSearchTool {

    /** 单批查询数上限：防止模型一口气发起过多搜索（API 成本与上下文长度双重约束） */
    static final int MAX_QUERIES = 6;
    /** 池大小：搜索是 IO 密集型，4 线程足以让单批 6 个查询的等待时间重叠 */
    static final int POOL_SIZE = 4;
    /** 单查询超时（秒）：一条挂死不能拖垮整批 */
    static final int QUERY_TIMEOUT_SECONDS = 10;

    private final WebSearchTool webSearchTool;
    private final ExecutorService executor;
    private final int queryTimeoutSeconds;

    public ParallelSearchTool(WebSearchTool webSearchTool) {
        this(webSearchTool, defaultExecutor(), QUERY_TIMEOUT_SECONDS);
    }

    /** 测试注入：可替换线程池与超时，验证并发上限与超时降级 */
    ParallelSearchTool(WebSearchTool webSearchTool, ExecutorService executor, int queryTimeoutSeconds) {
        this.webSearchTool = webSearchTool;
        this.executor = executor;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    private static ExecutorService defaultExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "research-search-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64), factory, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Tool(description = "Search the web with multiple queries in parallel. "
            + "Provide 2-6 search dimension queries in one call; results are grouped per query. "
            + "Prefer this over repeated single webSearch calls when investigating several dimensions at once.")
    public String parallelSearch(
            @ToolParam(description = "2-6 search queries, one per research dimension") List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return "Error: no queries provided.";
        }
        List<String> cleaned = cleanQueries(queries);
        if (cleaned.isEmpty()) {
            return "Error: no valid queries provided.";
        }

        // 全部先提交（并发执行），再按提交顺序收集——总耗时 ≈ 最慢一条，而不是各条之和
        List<Future<String>> futures = new ArrayList<>(cleaned.size());
        for (String query : cleaned) {
            futures.add(executor.submit(() -> webSearchTool.searchWeb(query)));
        }

        StringBuilder output = new StringBuilder();
        int failed = 0;
        for (int i = 0; i < cleaned.size(); i++) {
            String query = cleaned.get(i);
            output.append("\n## 查询: ").append(query).append('\n');
            try {
                output.append(futures.get(i).get(queryTimeoutSeconds, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                // 打断底层任务并释放线程；该查询降级为占位，其余照常
                futures.get(i).cancel(true);
                failed++;
                output.append("（该查询超时，已放弃）");
            } catch (ExecutionException e) {
                failed++;
                Throwable cause = e.getCause();
                output.append("（该查询失败：").append(cause == null ? "未知原因" : cause.getMessage()).append('）');
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return output.append("\n（已中断，剩余结果丢弃）").toString();
            }
        }
        output.insert(0, "共 " + cleaned.size() + " 个查询"
                + (failed > 0 ? "，其中 " + failed + " 个未成功" : "") + "：\n");
        return output.toString();
    }

    /** 清洗：trim → 丢空白 → 去重保序 → 截断到单批上限 */
    private List<String> cleanQueries(List<String> queries) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String query : queries) {
            if (query == null) {
                continue;
            }
            String trimmed = query.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            seen.add(trimmed);
            if (seen.size() >= MAX_QUERIES) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }
}
