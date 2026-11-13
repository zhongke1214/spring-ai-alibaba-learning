package space.kmind.zk.mcpServer.server;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * MCP Server 侧：只回答「我有什么能力」，不关心谁在调、为什么调。
 * 对应正文 2.1.3 —— Server 的职责要极其收敛。
 */
@Component
public class LocalMcpCapabilities {

    /** 白名单根目录：所有文件访问都被限制在这里面 */
    private static final Path BASE = Path.of("mcp-data").toAbsolutePath().normalize();

    // ==================== Tools：模型自己决定调不调 ====================

    @McpTool(name = "get_current_time", description = "获取服务器当前时间，ISO-8601 格式")
    public String getCurrentTime() {
        return ZonedDateTime.now().toString();
    }

    @McpTool(
            name = "read_text_file",
            description = "读取白名单目录 mcp-data 下的文本文件；filename 是相对文件名，例如 hello.txt")
    public String readTextFile(
            @McpToolParam(description = "相对文件名，不含路径分隔符", required = true) String filename) {
        return read(filename);
    }

    // ==================== Resources：应用决定给不给模型看 ====================

    @McpResource(
            uri = "file:///mcp-data/{name}",
            name = "whitelisted-file",
            description = "白名单目录下某个文本文件的内容；由 Host 决定何时附加到上下文")
    public String whitelistedFile(String name) {
        return read(name);
    }

    // ==================== Prompts：用户显式触发 ====================

    @McpPrompt(name = "daily-report", description = "生成日报模板，用户在前端点一下即可触发")
    public GetPromptResult dailyReport(
            @McpArg(name = "topic", description = "日报主题", required = true) String topic) {
        String text = "请基于今天的工作记录，生成一份「" + topic + "」日报：\n"
                + "1) 今天完成的三件事；\n"
                + "2) 当前的一个阻塞点；\n"
                + "3) 明天的第一步。";
        return new GetPromptResult("daily-report",
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }

    // ==================== 共用：白名单校验 ====================

    /**
     * 安全点：先 normalize 再比 startsWith。
     * 少了这一步，filename 传 "../../etc/passwd" 就能读到白名单外的文件。
     */
    private String read(String name) {
        if (name == null || name.isBlank()) {
            return "ERROR: filename required";
        }
        Path target = BASE.resolve(name).normalize();
        if (!target.startsWith(BASE)) {
            return "ERROR: file not allowed";
        }
        if (!Files.isRegularFile(target)) {
            return "ERROR: file not found";
        }
        try {
            return Files.readString(target);
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}