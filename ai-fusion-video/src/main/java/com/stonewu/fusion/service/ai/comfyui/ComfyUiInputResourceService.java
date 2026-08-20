package com.stonewu.fusion.service.ai.comfyui;

import cn.hutool.core.util.StrUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiNativeClient;
import com.stonewu.fusion.service.ai.comfyui.client.ComfyUiUploadResult;
import com.stonewu.fusion.service.ai.proxy.AiProxySupport;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Loads platform image references and uploads them through ComfyUI /upload/image. */
@Service
public class ComfyUiInputResourceService {

    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 50 * 1024 * 1024;

    private final ComfyUiNativeClient nativeClient;
    private final OkHttpClient baseClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build();

    public ComfyUiInputResourceService(ComfyUiNativeClient nativeClient) {
        this.nativeClient = nativeClient;
    }

    public List<String> uploadImages(ApiConfig apiConfig,
                                     String taskKey,
                                     String field,
                                     List<String> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        List<String> uploaded = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            ImageBytes image = loadImage(apiConfig, sources.get(index));
            String extension = extensionFor(image.contentType());
            String fileName = sanitizePart(taskKey) + "-" + sanitizePart(field)
                    + "-" + index + "." + extension;
            ComfyUiUploadResult result = nativeClient.uploadImage(
                    apiConfig, image.bytes(), fileName, image.contentType(), "ai-fusion-video");
            uploaded.add(result.workflowValue());
        }
        return List.copyOf(uploaded);
    }

    /** 上传参考音频（音色/配乐）到 ComfyUI，返回工作流可用的文件名列表。 */
    public List<String> uploadAudios(ApiConfig apiConfig,
                                     String taskKey,
                                     String field,
                                     List<String> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        List<String> uploaded = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            AudioBytes audio = loadAudio(apiConfig, sources.get(index));
            String extension = extensionFor(audio.contentType());
            String fileName = sanitizePart(taskKey) + "-" + sanitizePart(field)
                    + "-" + index + "." + extension;
            ComfyUiUploadResult result = nativeClient.uploadAudio(
                    apiConfig, audio.bytes(), fileName, audio.contentType(), "ai-fusion-video");
            uploaded.add(result.workflowValue());
        }
        return List.copyOf(uploaded);
    }

    private AudioBytes loadAudio(ApiConfig apiConfig, String source) {
        if (StrUtil.isBlank(source)) {
            throw new BusinessException(400, "ComfyUI 音频输入不能为空");
        }
        String value = source.trim();
        if (value.startsWith("data:")) {
            return parseAudioDataUri(value);
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return downloadAudioHttp(apiConfig, value);
        }
        throw new BusinessException(400,
                "ComfyUI 音频输入必须先转换为 Data URI 或公网 URL");
    }

    private AudioBytes parseAudioDataUri(String value) {
        int comma = value.indexOf(',');
        if (comma <= 5) {
            throw new BusinessException(400, "ComfyUI 音频 Data URI 格式无效");
        }
        String metadata = value.substring(5, comma);
        String[] metadataParts = metadata.split(";");
        boolean base64 = false;
        for (int index = 1; index < metadataParts.length; index++) {
            if ("base64".equalsIgnoreCase(metadataParts[index].trim())) {
                base64 = true;
                break;
            }
        }
        if (!base64) {
            throw new BusinessException(400, "ComfyUI 音频 Data URI 必须使用 base64");
        }
        String contentType = metadataParts[0].trim().toLowerCase(Locale.ROOT);
        requireAudioContentType(contentType);
        String encoded = value.substring(comma + 1);
        int maxEncodedLength = 4 * ((MAX_AUDIO_BYTES + 2) / 3);
        if (encoded.length() > maxEncodedLength) {
            throw new BusinessException(400, "ComfyUI 单个输入音频不能超过 50MB");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length <= 0 || bytes.length > MAX_AUDIO_BYTES) {
                throw new BusinessException(400, "ComfyUI 单个输入音频大小必须在 1B 到 50MB 之间");
            }
            return new AudioBytes(bytes, contentType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ComfyUI 音频 Data URI base64 无效");
        }
    }

    private AudioBytes downloadAudioHttp(ApiConfig apiConfig, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ComfyUI 音频 URL 无效");
        }
        if (uri.getHost() == null) {
            throw new BusinessException(400, "ComfyUI 音频 URL 缺少主机");
        }
        Request request = new Request.Builder().url(value).header("Accept", "audio/*").get().build();
        OkHttpClient client = AiProxySupport.okHttpClient(baseClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(502,
                        "下载 ComfyUI 音频输入失败，HTTP " + response.code());
            }
            long length = response.body().contentLength();
            if (length > MAX_AUDIO_BYTES) {
                throw new BusinessException(400, "ComfyUI 单个输入音频不能超过 50MB");
            }
            byte[] bytes = readBounded(response.body().byteStream(), MAX_AUDIO_BYTES);
            if (bytes.length <= 0 || bytes.length > MAX_AUDIO_BYTES) {
                throw new BusinessException(400, "ComfyUI 单个输入音频大小必须在 1B 到 50MB 之间");
            }
            if (response.body().contentType() == null) {
                throw new BusinessException(502, "ComfyUI 音频输入响应缺少 Content-Type");
            }
            String contentType = response.body().contentType().toString()
                    .split(";", 2)[0].toLowerCase(Locale.ROOT);
            contentType = resolveAudioContentType(contentType, value);
            requireAudioContentType(contentType);
            return new AudioBytes(bytes, contentType);
        } catch (IOException e) {
            throw new BusinessException(502,
                    "下载 ComfyUI 音频输入异常: " + StrUtil.blankToDefault(e.getMessage(), "I/O error"));
        }
    }

    /** Content-Type 缺失或为 octet-stream 时，按 URL 扩展名推断音频类型。 */
    private String resolveAudioContentType(String contentType, String url) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("audio/")) {
            return ct;
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".amr")) return "audio/amr";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".wma")) return "audio/x-ms-wma";
        if (lower.endsWith(".weba")) return "audio/webm";
        if (lower.endsWith(".mp4")) return "audio/mp4";
        return ct;
    }

    private void requireAudioContentType(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!ct.startsWith("audio/")) {
            throw new BusinessException(400, "ComfyUI 输入文件不是受支持的音频: " + contentType);
        }
    }

    private ImageBytes loadImage(ApiConfig apiConfig, String source) {
        if (StrUtil.isBlank(source)) {
            throw new BusinessException(400, "ComfyUI 图片输入不能为空");
        }
        String value = source.trim();
        if (value.startsWith("data:")) {
            return parseDataUri(value);
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return downloadHttp(apiConfig, value);
        }
        throw new BusinessException(400,
                "ComfyUI 图片输入必须先转换为 Data URI 或公网 URL；本地素材请启用 data_uri 传输");
    }

    private ImageBytes parseDataUri(String value) {
        int comma = value.indexOf(',');
        if (comma <= 5) {
            throw new BusinessException(400, "ComfyUI 图片 Data URI 格式无效");
        }
        String metadata = value.substring(5, comma);
        String[] metadataParts = metadata.split(";");
        boolean base64 = false;
        for (int index = 1; index < metadataParts.length; index++) {
            if ("base64".equalsIgnoreCase(metadataParts[index].trim())) {
                base64 = true;
                break;
            }
        }
        if (!base64) {
            throw new BusinessException(400, "ComfyUI 图片 Data URI 必须使用 base64");
        }
        String contentType = metadataParts[0].trim().toLowerCase(Locale.ROOT);
        requireImageContentType(contentType);
        String encoded = value.substring(comma + 1);
        int maxEncodedLength = 4 * ((MAX_IMAGE_BYTES + 2) / 3);
        if (encoded.length() > maxEncodedLength) {
            throw new BusinessException(400, "ComfyUI 单张输入图片不能超过 20MB");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            requireSize(bytes.length);
            return new ImageBytes(bytes, contentType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ComfyUI 图片 Data URI base64 无效");
        }
    }

    private ImageBytes downloadHttp(ApiConfig apiConfig, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "ComfyUI 图片 URL 无效");
        }
        if (uri.getHost() == null) {
            throw new BusinessException(400, "ComfyUI 图片 URL 缺少主机");
        }
        Request request = new Request.Builder().url(value).header("Accept", "image/*").get().build();
        OkHttpClient client = AiProxySupport.okHttpClient(baseClient, apiConfig);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(502,
                        "下载 ComfyUI 图片输入失败，HTTP " + response.code());
            }
            long length = response.body().contentLength();
            if (length > MAX_IMAGE_BYTES) {
                throw new BusinessException(400, "ComfyUI 单张输入图片不能超过 20MB");
            }
            byte[] bytes = readBounded(response.body().byteStream(), MAX_IMAGE_BYTES);
            requireSize(bytes.length);
            if (response.body().contentType() == null) {
                throw new BusinessException(502, "ComfyUI 图片输入响应缺少 Content-Type");
            }
            String contentType = response.body().contentType().toString()
                    .split(";", 2)[0].toLowerCase(Locale.ROOT);
            requireImageContentType(contentType);
            return new ImageBytes(bytes, contentType);
        } catch (IOException e) {
            throw new BusinessException(502,
                    "下载 ComfyUI 图片输入异常: " + StrUtil.blankToDefault(e.getMessage(), "I/O error"));
        }
    }

    private void requireSize(int size) {
        if (size <= 0 || size > MAX_IMAGE_BYTES) {
            throw new BusinessException(400, "ComfyUI 单张输入图片大小必须在 1B 到 20MB 之间");
        }
    }

    private void requireImageContentType(String contentType) {
        if (contentType == null || !switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif",
                 "image/bmp", "image/tiff" -> true;
            default -> false;
        }) {
            throw new BusinessException(400, "ComfyUI 输入文件不是受支持的栅格图片");
        }
    }

    private String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/tiff" -> "tiff";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a";
            case "audio/aac" -> "aac";
            case "audio/ogg" -> "ogg";
            case "audio/flac" -> "flac";
            case "audio/amr" -> "amr";
            case "audio/opus" -> "opus";
            case "audio/x-ms-wma" -> "wma";
            case "audio/webm" -> "weba";
            default -> throw new BusinessException(400, "ComfyUI 输入文件类型不支持: " + contentType);
        };
    }

    static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > maxBytes - read) {
                    throw new BusinessException(400, "ComfyUI 单张输入图片不能超过 20MB");
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        }
    }

    private String sanitizePart(String value) {
        String normalized = StrUtil.blankToDefault(value, "task")
                .replaceAll("[^a-zA-Z0-9_-]", "-");
        return normalized.getBytes(StandardCharsets.UTF_8).length > 64
                ? normalized.substring(0, 64) : normalized;
    }

    private record ImageBytes(byte[] bytes, String contentType) {
    }

    private record AudioBytes(byte[] bytes, String contentType) {
    }
}
