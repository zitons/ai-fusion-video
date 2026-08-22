package com.stonewu.fusion.service.generation.video;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.mapper.asset.AssetItemMapper;
import com.stonewu.fusion.mapper.generation.VideoTaskMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardItemMapper;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.stonewu.fusion.service.generation.video.consumer.VideoGenerationConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 连续镜头串行生成链：
 * 一个视频一个视频按顺序发送请求；上一镜头视频完成并提取真实尾帧后，
 * 把该帧作为下一镜头的参考图片（追加到 referenceImageUrls），再提交下一个请求。
 * 串行由设计保证：每个步骤只在上一任务完成后才提交下一个。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoChainGenerator {

    private final StoryboardItemMapper storyboardItemMapper;
    private final AssetItemMapper assetItemMapper;
    private final VideoTaskMapper videoTaskMapper;
    private final AiModelService aiModelService;
    private final VideoGenerationConsumer videoGenerationConsumer;
    private final VideoFrameExtractor videoFrameExtractor;
    private final GenerationModelCapabilityService generationModelCapabilityService;
    private final VideoChainMapper chainMapper;
    private final VideoChainStepMapper stepMapper;

    /** 创建连续链并提交第一个镜头。itemIds 按生成顺序排列。 */
    public long createChain(Long userId, Long modelId, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("连续镜头链不能为空");
        }
        AiModel model = resolveModel(modelId);
        VideoChain chain = new VideoChain();
        chain.setUserId(userId);
        chain.setModelId(model.getId());
        chain.setTotalSteps(itemIds.size());
        chain.setStatus(0);
        chainMapper.insert(chain);
        for (int i = 0; i < itemIds.size(); i++) {
            StoryboardItem item = storyboardItemMapper.selectById(itemIds.get(i));
            if (item == null) {
                throw new IllegalArgumentException("分镜镜头不存在: " + itemIds.get(i));
            }
            VideoChainStep step = new VideoChainStep();
            step.setChainId(chain.getId());
            step.setSeq(i);
            step.setStoryboardItemId(item.getId());
            step.setPrompt(item.getVideoPrompt());
            step.setReferenceImageUrls(toJson(collectReferenceImages(item)));
            step.setReferenceAudioUrls(toJson(collectReferenceAudios(item)));
            step.setDuration(item.getDuration() != null ? item.getDuration().intValue() : 5);
            step.setStatus(0);
            stepMapper.insert(step);
        }
        submitStep(chain.getId(), 0);
        return chain.getId();
    }

    private void submitStep(Long chainId, int seq) {
        VideoChainStep step = loadStep(chainId, seq);
        VideoChain chain = chainMapper.selectById(chainId);
        if (step == null || chain == null || step.getStatus() != null && step.getStatus() != 0) {
            return;
        }
        AiModel model = aiModelService.getById(chain.getModelId());
        if (model == null) {
            failChain(chainId, "链模型不存在: " + chain.getModelId());
            return;
        }
        VideoTask task = VideoTask.builder()
                .prompt(step.getPrompt())
                .generateMode("text2video")
                .referenceImageUrls(step.getReferenceImageUrls())
                .referenceAudioUrls(step.getReferenceAudioUrls())
                .duration(step.getDuration() != null ? step.getDuration() : 5)
                .modelId(model.getId())
                .count(1)
                .userId(chain.getUserId())
                .category(step.getStoryboardItemId() != null
                        ? "storyboard:" + step.getStoryboardItemId() : null)
                .build();
        try {
            generationModelCapabilityService.validateVideoTask(model, task);
        } catch (Exception e) {
            failStep(step, "任务校验失败: " + e.getMessage());
            failChain(chainId, "任务校验失败: " + e.getMessage());
            return;
        }
        String taskId = videoGenerationConsumer.submitTask(task);
        step.setTaskId(parseTaskId(taskId));
        step.setStatus(1);
        stepMapper.updateById(step);
        log.info("[VideoChain] 已提交链内镜头: chain={}, seq={}/{}, item={}, taskId={}",
                chainId, seq + 1, chain.getTotalSteps(), step.getStoryboardItemId(), taskId);
    }

    /** 轮询：上一任务完成 -> 提取尾帧 -> 追加为下一镜参考图 -> 提交下一镜。串行。 */
    @Scheduled(fixedDelay = 10000)
    public void drive() {
        List<VideoChainStep> active = stepMapper.selectList(new LambdaQueryWrapper<VideoChainStep>()
                .eq(VideoChainStep::getStatus, 1)
                .isNotNull(VideoChainStep::getTaskId)
                .last("LIMIT 20"));
        for (VideoChainStep step : active) {
            try {
                advance(step);
            } catch (Exception e) {
                log.error("[VideoChain] 驱动失败 chain={}, step={}", step.getChainId(), step.getId(), e);
                failStep(step, e.getMessage());
                failChain(step.getChainId(), e.getMessage());
            }
        }
    }

    private void advance(VideoChainStep step) {
        VideoTask task = step.getTaskId() != null ? videoTaskMapper.selectById(step.getTaskId()) : null;
        if (task == null) {
            return;
        }
        if (task.getStatus() != null && task.getStatus() == 2) {
            String videoUrl = null;
            if (step.getStoryboardItemId() != null) {
                StoryboardItem item = storyboardItemMapper.selectById(step.getStoryboardItemId());
                if (item != null) {
                    videoUrl = item.getGeneratedVideoUrl();
                }
            }
            step.setStatus(2);
            stepMapper.updateById(step);
            VideoChainStep next = loadStep(step.getChainId(), step.getSeq() + 1);
            if (next == null) {
                finishChain(step.getChainId());
                return;
            }
            if (StrUtil.isBlank(videoUrl)) {
                log.warn("[VideoChain] 镜头完成但无视频回填,链中断: chain={}, seq={}", step.getChainId(), step.getSeq());
                failChain(step.getChainId(), "镜头无视频回填: seq=" + (step.getSeq() + 1));
                return;
            }
            // 提取上一镜真实尾帧 -> 追加为下一镜参考图
            VideoFrameExtractor.ExtractedFrames frames = videoFrameExtractor.extract(videoUrl, false, true);
            if (StrUtil.isBlank(frames.lastFrameUrl())) {
                log.warn("[VideoChain] 尾帧提取失败,链中断: chain={}, seq={}, video={}", step.getChainId(), step.getSeq(), videoUrl);
                failChain(step.getChainId(), "尾帧提取失败: " + videoUrl);
                return;
            }
            List<String> refs = parseStringList(next.getReferenceImageUrls());
            refs.add(frames.lastFrameUrl());
            next.setReferenceImageUrls(toJson(refs));
            // 提示词同步追加声明：上一镜结束画面作为参考图（不是首帧）
            String prompt = next.getPrompt();
            if (StrUtil.isNotBlank(prompt) && !prompt.contains("上一镜头结束画面参考")) {
                int pictureNumber = refs.size();
                next.setPrompt(prompt + "\n图片 " + pictureNumber
                        + "（<Picture " + pictureNumber + ">）是上一镜头结束画面参考，"
                        + "只用于场景、光线与人物位置的延续参考，不是首帧，不锁定构图。");
            }
            next.setStatus(0);
            stepMapper.updateById(next);
            submitStep(next.getChainId(), next.getSeq());
        } else if (task.getStatus() != null && task.getStatus() == 3) {
            failStep(step, StrUtil.blankToDefault(task.getErrorMsg(), "任务失败"));
            failChain(step.getChainId(), "任务失败: " + StrUtil.blankToDefault(task.getErrorMsg(), "未知"));
        }
    }

    // ---- helpers ----

    private List<String> collectReferenceImages(StoryboardItem item) {
        List<String> urls = new ArrayList<>();
        if (StrUtil.isNotBlank(item.getCharacterIds())) {
            try {
                JSONArray arr = JSONUtil.parseArray(item.getCharacterIds());
                for (int i = 0; i < arr.size() && urls.size() < 3; i++) {
                    AssetItem a = assetItemMapper.selectById(arr.getLong(i));
                    if (a != null && StrUtil.isNotBlank(a.getImageUrl())) {
                        urls.add(a.getImageUrl());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (item.getSceneAssetItemId() != null && urls.size() < 3) {
            AssetItem scene = assetItemMapper.selectById(item.getSceneAssetItemId());
            if (scene != null && StrUtil.isNotBlank(scene.getImageUrl())) {
                urls.add(scene.getImageUrl());
            }
        }
        return urls;
    }

    private List<String> collectReferenceAudios(StoryboardItem item) {
        List<String> audios = new ArrayList<>();
        if (StrUtil.isNotBlank(item.getCharacterIds())) {
            try {
                JSONArray arr = JSONUtil.parseArray(item.getCharacterIds());
                for (int i = 0; i < arr.size(); i++) {
                    AssetItem a = assetItemMapper.selectById(arr.getLong(i));
                    if (a != null && StrUtil.isNotBlank(a.getProperties())) {
                        try {
                            JSONObject props = JSONUtil.parseObj(a.getProperties());
                            String voice = props.getStr("voice_url");
                            if (StrUtil.isNotBlank(voice)) {
                                audios.add(voice);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return audios;
    }

    private AiModel resolveModel(Long modelId) {
        AiModel model = modelId != null ? aiModelService.getById(modelId) : aiModelService.getDefaultByType(3);
        if (model == null || model.getId() == null) {
            throw new IllegalArgumentException("未配置可用的视频生成模型");
        }
        return model;
    }

    private List<String> parseStringList(String json) {
        List<String> out = new ArrayList<>();
        if (StrUtil.isBlank(json)) {
            return out;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                String v = arr.getStr(i);
                if (StrUtil.isNotBlank(v)) {
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String toJson(List<String> values) {
        return values == null || values.isEmpty() ? null : JSONUtil.toJsonStr(values);
    }

    private Long parseTaskId(String taskId) {
        try {
            return Long.valueOf(taskId);
        } catch (Exception e) {
            return null;
        }
    }

    private VideoChainStep loadStep(Long chainId, int seq) {
        return stepMapper.selectOne(new LambdaQueryWrapper<VideoChainStep>()
                .eq(VideoChainStep::getChainId, chainId)
                .eq(VideoChainStep::getSeq, seq));
    }

    private void failStep(VideoChainStep step, String msg) {
        step.setStatus(3);
        step.setErrorMsg(StrUtil.sub(msg, 0, 900));
        stepMapper.updateById(step);
    }

    private void failChain(Long chainId, String msg) {
        VideoChain chain = chainMapper.selectById(chainId);
        if (chain != null) {
            chain.setStatus(2);
            chain.setErrorMsg(StrUtil.sub(msg, 0, 900));
            chainMapper.updateById(chain);
        }
    }

    private void finishChain(Long chainId) {
        VideoChain chain = chainMapper.selectById(chainId);
        if (chain != null) {
            chain.setStatus(1);
            chainMapper.updateById(chain);
            log.info("[VideoChain] 链完成: chain={}, steps={}", chainId, chain.getTotalSteps());
        }
    }

    @com.baomidou.mybatisplus.annotation.TableName("afv_video_chain")
    public static class VideoChain {
        @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
        private Long id;
        private Long userId;
        private Long modelId;
        private Integer totalSteps;
        private Integer status;
        private String errorMsg;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getModelId() { return modelId; }
        public void setModelId(Long modelId) { this.modelId = modelId; }
        public Integer getTotalSteps() { return totalSteps; }
        public void setTotalSteps(Integer totalSteps) { this.totalSteps = totalSteps; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public String getErrorMsg() { return errorMsg; }
        public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    }

    @com.baomidou.mybatisplus.annotation.TableName("afv_video_chain_step")
    public static class VideoChainStep {
        @com.baomidou.mybatisplus.annotation.TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
        private Long id;
        private Long chainId;
        private Integer seq;
        private Long storyboardItemId;
        private String prompt;
        private String referenceImageUrls;
        private String referenceAudioUrls;
        private Integer duration;
        private Long taskId;
        private Integer status;
        private String errorMsg;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getChainId() { return chainId; }
        public void setChainId(Long chainId) { this.chainId = chainId; }
        public Integer getSeq() { return seq; }
        public void setSeq(Integer seq) { this.seq = seq; }
        public Long getStoryboardItemId() { return storyboardItemId; }
        public void setStoryboardItemId(Long storyboardItemId) { this.storyboardItemId = storyboardItemId; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public String getReferenceImageUrls() { return referenceImageUrls; }
        public void setReferenceImageUrls(String referenceImageUrls) { this.referenceImageUrls = referenceImageUrls; }
        public String getReferenceAudioUrls() { return referenceAudioUrls; }
        public void setReferenceAudioUrls(String referenceAudioUrls) { this.referenceAudioUrls = referenceAudioUrls; }
        public Integer getDuration() { return duration; }
        public void setDuration(Integer duration) { this.duration = duration; }
        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public String getErrorMsg() { return errorMsg; }
        public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    }

    @Mapper
    public interface VideoChainMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<VideoChain> {
    }

    @Mapper
    public interface VideoChainStepMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<VideoChainStep> {
    }
}
