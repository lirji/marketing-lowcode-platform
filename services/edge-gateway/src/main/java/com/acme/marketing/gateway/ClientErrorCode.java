package com.acme.marketing.gateway;

import com.acme.marketing.platform.error.DomainException;

/** 诊断协议的固定错误目录，不将原始请求或解析错误回显给调用方。 */
enum ClientErrorCode {
    INPUT_INVALID("CLIENT_ERROR_INPUT_INVALID", "错误诊断事件格式无效"),
    REQUEST_TOO_LARGE("CLIENT_ERROR_REQUEST_TOO_LARGE", "错误诊断事件不能超过1024字节");

    private final String code;
    private final String message;
    ClientErrorCode(String code, String message) { this.code = code; this.message = message; }
    DomainException invalid() { return new DomainException(code, message); }
}
