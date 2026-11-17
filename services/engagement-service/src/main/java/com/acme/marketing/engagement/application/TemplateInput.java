package com.acme.marketing.engagement.application;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DomainException;
import java.util.regex.Pattern;

/** 在写库和幂等回放前约束模板输入，避免SQL截断、变量分隔符歧义和不可精确回显的版本。 */
public final class TemplateInput {
    private static final int MAX_TEMPLATE_ID_LENGTH = 128;
    private static final int MAX_CHANNEL_LENGTH = 32;
    private static final int MAX_CONTENT_LENGTH = 8000;
    private static final int MAX_REQUIRED_VARIABLES_COUNT = 32;
    private static final long MAX_VERSION = 9_007_199_254_740_991L;
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,59}");
    private static final String EMAIL_CHANNEL_CODE = "EMAIL";
    private static final String EMAIL_UNSUBSCRIBE_TEXT = "unsubscribe";
    private static final String INVALID_CODE = "TEMPLATE_INPUT_INVALID";
    private static final String INVALID_MESSAGE = "模板ID限128字符、渠道限32字符且不能含空白或控制字符；版本须为正安全整数，正文须为1–8000字符；最多32个变量，每个为1–60位字母、数字、下划线、点或短横线，且以字母或下划线开头";
    private static final String VERSION_CONFLICT_CODE = "TEMPLATE_VERSION_EXISTS";
    private static final String VERSION_CONFLICT_MESSAGE = "模板版本已存在，原内容未被覆盖；请刷新最新版本并保留修改后选择新版本";
    private static final String UNSUBSCRIBE_CODE = "UNSUBSCRIBE_NOTICE_REQUIRED";
    private static final String UNSUBSCRIBE_MESSAGE = "邮件模板必须包含 unsubscribe 退订说明";
    private TemplateInput() { }

    /** 不自动裁剪ID、正文或变量名，确保保存的内容与操作者确认的请求完全一致。 */
    public static void validate(EngagementService.TemplateRequest request) {
        if (request == null || !token(request.templateId(), MAX_TEMPLATE_ID_LENGTH) || !token(request.channel(), MAX_CHANNEL_LENGTH)
                || request.version() < 1 || request.version() > MAX_VERSION
                || request.content() == null || request.content().isBlank() || request.content().length() > MAX_CONTENT_LENGTH
                || request.requiredVariables() == null || request.requiredVariables().size() > MAX_REQUIRED_VARIABLES_COUNT
                || request.requiredVariables().stream().anyMatch(value -> value == null || !VARIABLE_NAME.matcher(value).matches())) {
            throw new DomainException(INVALID_CODE, INVALID_MESSAGE);
        }
        if (EMAIL_CHANNEL_CODE.equals(request.channel()) && !request.content().contains(EMAIL_UNSUBSCRIBE_TEXT))
            throw new ConflictException(UNSUBSCRIBE_CODE, UNSUBSCRIBE_MESSAGE);
    }

    /** 数据库唯一键是并发创建同版本的最终裁决，不以预查询代替完整性约束。 */
    public static ConflictException versionExists() {
        return new ConflictException(VERSION_CONFLICT_CODE, VERSION_CONFLICT_MESSAGE);
    }

    private static boolean token(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character));
    }
}
