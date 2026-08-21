package com.stonewu.fusion.service.ai.tool.generation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.generation.video.VideoFrameExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 从视频中提取首帧或尾帧并保存为图片。
 * 用于连贯镜头：本镜头首帧直接使用上一镜头视频的真实尾帧，保证无缝衔接。
 */
@Component
@RequiredArgsConstructor
public class ExtractVideoFrameToolExecutor implements ToolExecutor {

    private final VideoFrameExtractor videoFrameExtractor;

    @Override
    public String getToolName() {
        return "extract_video_frame";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getDisplayName() {
        return "提取视频帧";
    }

    @Override
    public String getToolDescription() {
        return """
                从指定视频中提取首帧或尾帧图片并保存到平台存储。
                使用时机：
                - 连贯镜头/一镜到底：目标镜头是上一镜头的动作延续时，首帧直接提取上一镜头视频的真实尾帧（不要用 generate_image 重新生成），保证画面无缝衔接。
                - 需要把已有视频的某一帧当作首帧/尾帧/参考图时。
                提取结果返回 imageUrl，可直接用于 update_storyboard_item_frame 等保存操作。
                """;
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "videoUrl": {
                            "type": "string",
                            "description": "视频 URL（/media/ 本地路径或公网 URL），必须存在。"
                        },
                        "frame": {
                            "type": "string",
                            "description": "提取哪一帧：first=视频首帧，last=视频尾帧（默认 last）",
                            "enum": ["first", "last"]
                        }
                    },
                    "required": ["videoUrl"]
                }
                """;
    }

    @Override
    public String execute(String toolInput, ToolExecutionContext context) {
        try {
            JSONObject params = StrUtil.isBlank(toolInput) ? new JSONObject() : JSONUtil.parseObj(toolInput);
            String videoUrl = params.getStr("videoUrl");
            if (StrUtil.isBlank(videoUrl)) {
                return errorResult("缺少 videoUrl");
            }
            String frame = StrUtil.blankToDefault(params.getStr("frame"), "last").trim().toLowerCase();
            boolean extractFirst = "first".equals(frame);
            boolean extractLast = "last".equals(frame);
            if (!extractFirst && !extractLast) {
                return errorResult("frame 仅支持 first 或 last");
            }

            VideoFrameExtractor.ExtractedFrames frames =
                    videoFrameExtractor.extract(videoUrl, extractFirst, extractLast);
            String imageUrl = extractFirst ? frames.firstFrameUrl() : frames.lastFrameUrl();
            if (StrUtil.isBlank(imageUrl)) {
                return errorResult("视频提帧失败（视频不存在或 ffmpeg 不可用）：" + videoUrl);
            }
            return JSONUtil.createObj()
                    .set("status", "success")
                    .set("frame", frame)
                    .set("imageUrl", imageUrl)
                    .set("sourceVideoUrl", videoUrl)
                    .toString();
        } catch (Exception e) {
            return errorResult("提取视频帧失败: " + e.getMessage());
        }
    }

    private String errorResult(String message) {
        return JSONUtil.createObj().set("status", "error").set("message", message).toString();
    }
}
