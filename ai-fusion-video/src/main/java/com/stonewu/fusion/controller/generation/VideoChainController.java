package com.stonewu.fusion.controller.generation;

import com.stonewu.fusion.common.CommonResult;
import com.stonewu.fusion.service.generation.video.VideoChainGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 连续镜头串行生成链接口 */
@RestController
@RequestMapping("/api/video/chain")
@RequiredArgsConstructor
public class VideoChainController {

    private final VideoChainGenerator videoChainGenerator;

    @PostMapping
    public CommonResult<Long> create(@RequestBody VideoChainCreateReqVO req) {
        if (req == null || req.getItemIds() == null || req.getItemIds().isEmpty()) {
            throw new IllegalArgumentException("itemIds 不能为空");
        }
        Long userId = com.stonewu.fusion.security.SecurityUtils.getCurrentUserId();
        long chainId = videoChainGenerator.createChain(userId, req.getModelId(), req.getItemIds());
        return CommonResult.success(chainId);
    }

    public static class VideoChainCreateReqVO {
        private List<Long> itemIds;
        private Long modelId;

        public List<Long> getItemIds() {
            return itemIds;
        }

        public void setItemIds(List<Long> itemIds) {
            this.itemIds = itemIds;
        }

        public Long getModelId() {
            return modelId;
        }

        public void setModelId(Long modelId) {
            this.modelId = modelId;
        }
    }
}
