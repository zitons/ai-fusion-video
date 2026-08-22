package com.stonewu.fusion.service.ai.tool.generation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.generation.video.VideoChainGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 连续镜头串行生成：一个视频一个视频发送请求，
 * 上一镜头视频完成并提取真实尾帧后，作为下一镜头的参考图片再提交下一个请求。
 */
@Component
@RequiredArgsConstructor
public class GenerateVideoChainToolExecutor implements ToolExecutor {

    private final VideoChainGenerator videoChainGenerator;

    @Override
    public String getToolName() {
        return "generate_video_chain";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getDisplayName() {
        return "连续镜头串行生成";
    }

    @Override
    public String getToolDescription() {
        return """
                把一组连续镜头按顺序串行生成视频（一个视频一个视频发送请求）：
                镜头 N 的视频完成 → 自动提取其真实尾帧 → 作为镜头 N+1 的参考图片（追加到 referenceImageUrls）→ 提交下一个请求。
                使用时机：
                - 多个镜头是同一动作/对话的直接延续（分镜 custom_data.continuous=true 的连续组）
                - 需要跨镜画面状态延续的镜头组（软衔接，不锁首帧）
                注意：链内串行生成（每个 5-8 分钟），提交后立即返回，无需等待；
                每个镜头需先有 videoPrompt（先执行仅提示词模式或已有提示词）。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "itemIds": {
                            "type": "array",
                            "items": { "type": "integer" },
                            "description": "分镜镜头ID列表，按生成顺序排列（连续镜头组，第一镜到最后镜）"
                        },
                        "modelId": {
                            "type": "integer",
                            "description": "视频模型ID（可选，不传用默认视频模型）"
                        }
                    },
                    "required": ["itemIds"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = StrUtil.isBlank(toolInput) ? new JSONObject() : JSONUtil.parseObj(toolInput);
            JSONArray ids = params.getJSONArray("itemIds");
            if (ids == null || ids.isEmpty()) {
                return errorResult("缺少 itemIds 或为空");
            }
            List<Long> itemIds = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Long id = ids.getLong(i);
                if (id != null) {
                    itemIds.add(id);
                }
            }
            if (itemIds.isEmpty()) {
                return errorResult("itemIds 无有效镜头ID");
            }
            Long chainId = videoChainGenerator.createChain(context.getUserId(), params.getLong("modelId"), itemIds);
            return JSONUtil.createObj()
                    .set("status", "chained")
                    .set("chainId", chainId)
                    .set("message", "已按顺序提交 " + itemIds.size()
                            + " 个连续镜头：一个视频一个视频生成，每个镜头完成自动提取真实尾帧并作为下一镜参考图片，无需等待，可到「生成记录」查看进度。")
                    .toString();
        } catch (Exception e) {
            return errorResult("创建连续生成链失败: " + e.getMessage());
        }
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
