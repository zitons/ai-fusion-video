import { http } from "@/lib/api/client";

/** 连续镜头串行生成链 API */
export const videoChainApi = {
  /** 创建串行生成链：一个视频一个视频生成，上一镜完成自动提取真实尾帧作为下一镜参考图。
   *  前提：所有镜头已生成 videoPrompt（先跑「批量生成视频提示词」）。 */
  create: (data: { itemIds: number[]; modelId?: number }) =>
    http.post<never, number>("/api/video/chain", data),
};
