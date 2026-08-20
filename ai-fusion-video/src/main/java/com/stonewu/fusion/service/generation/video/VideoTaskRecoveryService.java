package com.stonewu.fusion.service.generation.video;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.entity.generation.VideoTask;
import com.stonewu.fusion.service.generation.video.strategy.comfyui.ComfyUiVideoStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 失败视频任务找回：任务曾因瞬时网络/超时被判失败，但 ComfyUI 输出可能仍在。
 * 在生成记录分页查询时触发，对符合条件的失败任务一次性查询 ComfyUI，
 * 已出片则重新下载、入库并标记完成——用户点「刷新记录」即可拿到视频。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTaskRecoveryService {

    /** 同一任务两次找回尝试的最小间隔，避免反复查询 ComfyUI。 */
    private static final long RATE_LIMIT_MILLIS = 10 * 60 * 1000L;

    private final VideoGenerationService videoGenerationService;
    private final ComfyUiVideoStrategy comfyUiVideoStrategy;
    private final Map<Long, Long> lastAttemptAt = new ConcurrentHashMap<>();

    public void tryRecoverPage(List<VideoTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (VideoTask task : tasks) {
            if (task == null || task.getId() == null || task.getStatus() == null
                    || task.getStatus() != 3) {
                continue;
            }
            if (!isTransientFailure(task)) {
                continue;
            }
            long now = System.currentTimeMillis();
            Long last = lastAttemptAt.get(task.getId());
            if (last != null && now - last < RATE_LIMIT_MILLIS) {
                continue;
            }
            lastAttemptAt.put(task.getId(), now);
            try {
                boolean recovered = comfyUiVideoStrategy.recover(task);
                if (recovered) {
                    log.info("[VideoRecovery] 失败任务找回成功: taskId={}", task.getTaskId());
                }
            } catch (Exception e) {
                log.debug("[VideoRecovery] 失败任务找回未命中（忽略）: taskId={}, reason={}",
                        task.getTaskId(), StrUtil.blankToDefault(e.getMessage(), "unknown"));
            }
        }
    }

    /** 仅对"瞬时类"失败尝试找回，避免对真实失败的重复查询。 */
    private boolean isTransientFailure(VideoTask task) {
        String message = task.getErrorMsg();
        if (StrUtil.isBlank(message)) {
            return false;
        }
        return message.contains("连接") || message.contains("connect")
                || message.contains("超时") || message.contains("timeout")
                || message.contains("轮询") || message.contains("执行失败");
    }
}
