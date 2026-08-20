package com.stonewu.fusion.service.generation.video.strategy.comfyui;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.generation.VideoItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiExecutionContext;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiGenerationExecutor;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiPreparedSubmission;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiStoredOutput;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowService;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiJobResult;
import com.stonewu.fusion.service.ai.tool.ToolResourceAccessGuard;
import com.stonewu.fusion.service.generation.video.VideoGenerationService;
import com.stonewu.fusion.service.generation.video.strategy.VideoGenerationStrategy;
import com.stonewu.fusion.service.storyboard.StoryboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Video generation through an administrator-published ComfyUI workflow. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComfyUiVideoStrategy implements VideoGenerationStrategy {

    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 5_000L;
    private static final long DEFAULT_TIMEOUT_MILLIS = 60L * 60L * 1_000L;
    private static final String STORYBOARD_CATEGORY_PREFIX = "storyboard:";

    private final AiModelService aiModelService;
    private final VideoGenerationService videoGenerationService;
    private final ComfyUiGenerationExecutor executor;
    private final StoryboardService storyboardService;
    private final ToolResourceAccessGuard accessGuard;

    @Override
    public String getName() {
        return ComfyUiWorkflowService.PLATFORM;
    }

    @Override
    public String submit(VideoTask task) {
        AiModel model = requireModel(task);
        ComfyUiExecutionContext context = executor.resolveContext(model, task.getWorkflowVersionId());
        ComfyUiPreparedSubmission submission = executor.prepare(
                context, task.getTaskId(), videoValues(task, model));
        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        if (items.isEmpty()) {
            throw new BusinessException(400, "视频任务缺少生成条目");
        }
        for (VideoItem item : items) {
            item.setPlatformTaskId(submission.promptId());
            videoGenerationService.updateItem(item);
        }
        executor.submit(submission);
        log.info("[ComfyUI Video] 已提交: taskId={}, promptId={}, workflowVersionId={}",
                task.getTaskId(), submission.promptId(), task.getWorkflowVersionId());
        return submission.promptId();
    }

    @Override
    public void poll(String platformTaskId, VideoTask task) {
        AiModel model = requireModel(task);
        ComfyUiExecutionContext context = executor.resolveContext(model, task.getWorkflowVersionId());
        ComfyUiJobResult job = executor.waitForJob(
                context, platformTaskId, pollInterval(model), timeout(model));
        List<ComfyUiStoredOutput> outputs = executor.storeOutputs(context, job);
        List<ComfyUiStoredOutput> videos = outputs.stream()
                .filter(output -> "video".equals(output.mediaType())
                        && "primary".equals(output.role()))
                .toList();
        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        if (videos.size() < items.size()) {
            throw new BusinessException(502,
                    "ComfyUI 返回视频数量不足，期望 " + items.size() + "，实际 " + videos.size());
        }
        applyOutputs(task, outputs, items);
    }

    /**
     * 失败任务找回：任务曾因瞬时网络/超时被判失败，但 ComfyUI 输出可能仍在。
     * 一次性查询任务状态，若已出片则重新下载、入库并标记任务完成。
     *
     * @return 是否找回成功
     */
    public boolean recover(VideoTask task) {
        AiModel model = requireModel(task);
        ComfyUiExecutionContext context = executor.resolveContext(model, task.getWorkflowVersionId());
        List<VideoItem> items = videoGenerationService.listItems(task.getId());
        String promptId = items.stream()
                .map(VideoItem::getPlatformTaskId)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        if (promptId == null) {
            return false;
        }
        ComfyUiJobResult job = executor.getJob(context, promptId);
        if (!job.completed()) {
            return false;
        }
        List<ComfyUiStoredOutput> outputs = executor.storeOutputs(context, job);
        applyOutputs(task, outputs, items);
        log.info("[ComfyUI Video] 失败任务找回成功: taskId={}, promptId={}",
                task.getTaskId(), promptId);
        return true;
    }

    private void applyOutputs(VideoTask task,
                              List<ComfyUiStoredOutput> outputs,
                              List<VideoItem> items) {
        List<ComfyUiStoredOutput> videos = outputs.stream()
                .filter(output -> "video".equals(output.mediaType())
                        && "primary".equals(output.role()))
                .toList();
        List<ComfyUiStoredOutput> covers = outputs.stream()
                .filter(output -> "image".equals(output.mediaType())
                        && "cover".equals(output.role()))
                .toList();
        for (int index = 0; index < items.size(); index++) {
            VideoItem item = items.get(index);
            ComfyUiStoredOutput video = index < videos.size() ? videos.get(index) : null;
            if (video == null) {
                continue;
            }
            item.setPlatformTaskId(task.getTaskId());
            item.setVideoUrl(video.url());
            item.setFileSize(video.size());
            item.setDuration(task.getDuration());
            if (!covers.isEmpty()) {
                item.setCoverUrl(covers.get(Math.min(index, covers.size() - 1)).url());
            }
            item.setStatus(1);
            item.setErrorMsg(null);
            videoGenerationService.updateItem(item);
        }
        task.setSuccessCount(items.size());
        task.setStatus(2);
        task.setErrorMsg(null);
        videoGenerationService.update(task);
        backfillStoryboard(task, outputs);
    }

    /**
     * 分镜回填：任务携带 storyboard:{itemId} 映射时，把完成视频写入分镜条目的
     * generatedVideoUrl（含 prompt、时长），让视频出现在分镜页面。
     */
    private void backfillStoryboard(VideoTask task, List<ComfyUiStoredOutput> outputs) {
        String category = task.getCategory();
        if (category == null || !category.startsWith(STORYBOARD_CATEGORY_PREFIX)) {
            return;
        }
        Long itemId;
        try {
            itemId = Long.parseLong(category.substring(STORYBOARD_CATEGORY_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        String videoUrl = outputs.stream()
                .filter(output -> "video".equals(output.mediaType())
                        && "primary".equals(output.role()))
                .map(ComfyUiStoredOutput::url)
                .findFirst()
                .orElse(null);
        if (videoUrl == null) {
            return;
        }
        try {
            StoryboardItem item = accessGuard.requireStoryboardItem(itemId, task.getUserId());
            item.setGeneratedVideoUrl(videoUrl);
            if (StrUtil.isNotBlank(task.getPrompt())) {
                item.setVideoPrompt(task.getPrompt());
            }
            if (task.getDuration() != null) {
                item.setDuration(BigDecimal.valueOf(task.getDuration()));
            }
            storyboardService.updateItem(item);
            log.info("[ComfyUI Video] 分镜视频回填: itemId={}, videoUrl={}", itemId, videoUrl);
        } catch (Exception e) {
            log.warn("[ComfyUI Video] 分镜视频回填失败(忽略): itemId={}, reason={}",
                    itemId, StrUtil.blankToDefault(e.getMessage(), "unknown"));
        }
    }

    @Override
    public boolean cancel(String platformTaskId, VideoTask task) {
        return executor.cancel(
                executor.resolveContext(requireModel(task), task.getWorkflowVersionId()),
                platformTaskId);
    }

    @Override
    public boolean persistsResults() {
        return true;
    }

    private AiModel requireModel(VideoTask task) {
        AiModel model = task == null || task.getModelId() == null
                ? null : aiModelService.getById(task.getModelId());
        if (model == null) throw new BusinessException(404, "ComfyUI 视频模型不存在");
        return model;
    }

    private Map<String, Object> videoValues(VideoTask task, AiModel model) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("prompt", StrUtil.blankToDefault(task.getPrompt(), ""));
        values.put("duration", task.getDuration());
        values.put("count", Math.max(1, task.getCount()));
        values.put("seed", task.getSeed() != null ? task.getSeed() : randomSeed());
        values.put("generateAudio", task.getGenerateAudio());
        int[] resolution = parseResolution(task.getResolution());
        if (resolution != null) {
            values.put("width", resolution[0]);
            values.put("height", resolution[1]);
        }
        Integer fps = configInt(model, "defaultFps");
        if (fps != null) values.put("fps", fps);
        if (StrUtil.isNotBlank(task.getFirstFrameImageUrl())) {
            values.put("firstFrame", task.getFirstFrameImageUrl());
        }
        if (StrUtil.isNotBlank(task.getLastFrameImageUrl())) {
            values.put("lastFrame", task.getLastFrameImageUrl());
        }
        List<String> images = parseList(task.getReferenceImageUrls());
        if (!images.isEmpty()) values.put("referenceImages", images);
        List<String> videos = parseList(task.getReferenceVideoUrls());
        if (!videos.isEmpty()) values.put("referenceVideos", videos);
        List<String> audios = parseList(task.getReferenceAudioUrls());
        if (!audios.isEmpty()) values.put("referenceAudios", audios);
        return values;
    }

    private List<String> parseList(String json) {
        if (StrUtil.isBlank(json)) return List.of();
        try {
            return new ArrayList<>(JSONUtil.parseArray(json).toList(String.class));
        } catch (RuntimeException e) {
            throw new BusinessException(400, "ComfyUI 参考素材列表不是合法 JSON 数组");
        }
    }

    private int[] parseResolution(String resolution) {
        if (StrUtil.isBlank(resolution) || !resolution.matches("\\d{2,5}x\\d{2,5}")) return null;
        String[] parts = resolution.split("x", 2);
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private long pollInterval(AiModel model) {
        return configLong(model, "comfyuiPollIntervalMillis", DEFAULT_POLL_INTERVAL_MILLIS);
    }

    private long timeout(AiModel model) {
        return configLong(model, "comfyuiTimeoutMillis", DEFAULT_TIMEOUT_MILLIS);
    }

    private Integer configInt(AiModel model, String key) {
        if (model == null || StrUtil.isBlank(model.getConfig())) return null;
        try {
            return JSONUtil.parseObj(model.getConfig()).getInt(key);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private long configLong(AiModel model, String key, long fallback) {
        if (model == null || StrUtil.isBlank(model.getConfig())) return fallback;
        try {
            JSONObject config = JSONUtil.parseObj(model.getConfig());
            Long value = config.getLong(key);
            return value != null && value > 0 ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private long randomSeed() {
        return ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    }
}
