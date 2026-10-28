package edu.zsc.ai.local;

final class LocalRequestException extends RuntimeException {
    private final int status;
    private final String reasonCode;

    LocalRequestException(int status, String reasonCode, String message) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    int status() { return status; }
    ErrorResponse response() { return new ErrorResponse(status, null, getMessage(), reasonCode); }

    static LocalRequestException forbidden() {
        return new LocalRequestException(403, "LOCAL_SCOPE_FORBIDDEN", "请求超出本地个人范围");
    }

    static LocalRequestException invalid() {
        return new LocalRequestException(400, "LOCAL_REQUEST_INVALID", "本地请求格式不合法");
    }

    static LocalRequestException storeUnavailable() {
        return new LocalRequestException(503, "LOCAL_STORE_UNAVAILABLE", "本地配置或凭证无法安全读取或保存");
    }

    static LocalRequestException notFound() {
        return new LocalRequestException(404, "CONNECTION_NOT_FOUND", "本地连接不存在");
    }

    static LocalRequestException conflict() {
        return new LocalRequestException(409, "CONNECTION_REVISION_CONFLICT", "连接已被修改，请重新加载后再试");
    }

    record ErrorResponse(int code, Object data, String message, String reasonCode) { }
}
