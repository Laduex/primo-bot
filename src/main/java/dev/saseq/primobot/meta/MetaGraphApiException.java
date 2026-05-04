package dev.saseq.primobot.meta;

public class MetaGraphApiException extends RuntimeException {
    private final int status;
    private final Integer code;
    private final Integer subcode;
    private final String type;
    private final String fbTraceId;
    private final String errorMessage;

    public MetaGraphApiException(int status,
                                 Integer code,
                                 Integer subcode,
                                 String type,
                                 String fbTraceId,
                                 String errorMessage) {
        super(buildMessage(status, code, subcode, type, fbTraceId, errorMessage));
        this.status = status;
        this.code = code;
        this.subcode = subcode;
        this.type = type == null ? "" : type;
        this.fbTraceId = fbTraceId == null ? "" : fbTraceId;
        this.errorMessage = errorMessage == null ? "Unknown Meta API error" : errorMessage;
    }

    public int status() {
        return status;
    }

    public Integer code() {
        return code;
    }

    public Integer subcode() {
        return subcode;
    }

    public String type() {
        return type;
    }

    public String fbTraceId() {
        return fbTraceId;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String briefMessage() {
        return "status=%d code=%s message=%s"
                .formatted(status, code == null ? "?" : code.toString(), errorMessage);
    }

    private static String buildMessage(int status,
                                       Integer code,
                                       Integer subcode,
                                       String type,
                                       String fbTraceId,
                                       String errorMessage) {
        return "Meta API error status=%d code=%s subcode=%s type=%s message=%s fbtrace_id=%s"
                .formatted(
                        status,
                        code == null ? "?" : code.toString(),
                        subcode == null ? "?" : subcode.toString(),
                        type == null ? "" : type,
                        errorMessage == null ? "Unknown Meta API error" : errorMessage,
                        fbTraceId == null ? "" : fbTraceId
                );
    }
}
