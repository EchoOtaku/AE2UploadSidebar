package dev.echo.uploadsidebar.core;

import java.util.Map;

/** Translate legacy v2 wire messages only on the client, preserving old-client readability. */
public final class ServerMessages {
    private static final Map<String, String> LEGACY_KEYS = Map.ofEntries(
        Map.entry("终端已关闭、不可用或网络未连接", "ae2_upload_sidebar.server.unavailable"),
        Map.entry("仅显示前 512 个可用供应器", "ae2_upload_sidebar.server.truncated"),
        Map.entry("读取供应器失败，请重新打开终端", "ae2_upload_sidebar.server.read_failed"),
        Map.entry("目标列表已失效，请刷新", "ae2_upload_sidebar.server.list_expired"),
        Map.entry("已处理此上传请求", "ae2_upload_sidebar.server.duplicate"),
        Map.entry("目标已失效或此目标已被使用，请刷新", "ae2_upload_sidebar.server.target_expired"),
        Map.entry("原供应器已离线、被替换、隐藏或变满，请刷新", "ae2_upload_sidebar.server.target_changed"),
        Map.entry("无法确定已编码样板槽位", "ae2_upload_sidebar.server.source_unknown"),
        Map.entry("请先编码样板，再点击供应器上传", "ae2_upload_sidebar.server.encode_first"),
        Map.entry("样板已改变，本次未上传，请重试", "ae2_upload_sidebar.server.pattern_changed"),
        Map.entry("所选供应器没有可接受此样板的空槽", "ae2_upload_sidebar.server.no_slot"),
        Map.entry("供应器处理异常，请检查样板槽后重试", "ae2_upload_sidebar.server.provider_error"),
        Map.entry("库存状态已改变，本次未上传，请刷新", "ae2_upload_sidebar.server.inventory_changed"),
        Map.entry("已上传 1 张样板", "ae2_upload_sidebar.status.uploaded"),
        Map.entry("已上传 1 张样板（库存已核对）", "ae2_upload_sidebar.server.upload_verified"),
        Map.entry("供应器处理失败，两边库存均未改变", "ae2_upload_sidebar.server.recovered"),
        Map.entry("库存状态异常，已停止此请求；请检查源样板与目标槽", "ae2_upload_sidebar.server.inventory_error"));

    private ServerMessages() { }

    public static String localizationKey(String message) {
        return LEGACY_KEYS.getOrDefault(message, message);
    }
}
