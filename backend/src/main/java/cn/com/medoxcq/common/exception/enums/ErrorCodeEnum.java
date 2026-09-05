package cn.com.medoxcq.common.exception.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCodeEnum {

    SUCCESS(200, "请求成功"),


    BAD_REQUEST(400, "请求参数不正确"),

    UNAUTHORIZED(401, "账号未登录"),

    FORBIDDEN(403, "没有该操作权限"),

    NOT_FOUND(404, "请求未找到"),

    METHOD_NOT_ALLOWED(405, "请求方法不正确"),

    LOCKED(423, "请求失败，请稍后重试"),

    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),


    INTERNAL_SERVER_ERROR(500, "系统异常"),

    NOT_IMPLEMENTED(501, "功能未实现/未开启"),


    MODEL_RUNNING(40060, "已有同类模型正在运行，请稍后重试"),

    MODEL_RUNNING_LIMIT(40061, "模型并发任务已达上限，请等待部分任务完成后再提交。"),

    BUSINESS_ERROR(40000, "业务异常"),

    REPEATED_REQUESTS(40010, "重复请求，请稍后重试"),

    ILLEGAL_ARGUMENT(40011, "非法参数"),

    INVALID_RELATED_RECORD_ID(40020, "数据验证失败，关联数据并不存在，请刷新后重试"),

    DATA_NOT_EXIST(40030, "数据不存在，请刷新后重试"),

    DATA__EXISTED(40031, "数据已经存在"),

    PARENT_IS_CHILD(40040, "不能设置自己的子节点为上级节点"),

    IMPORT_FILE_ERROR(40050, "导入文件为空或格式不正确"),

    INVALID_USERNAME_OR_PASSWORD(40100, "用户名或密码错误"),

    INVALID_USER_STATUS(40110, "用户账号已被锁定"),

    INVALID_GRANT_TYPE(40120, "授权类型错误"),

    FORBIDDEN_SUPER_ADMIN_USER(40130, "禁止操作超级管理员用户"),

    FORBIDDEN_SUPER_ADMIN_ROLE(40140, "禁止操作超级管理员角色"),

    CODE_FAILED_EXCEED(40150, "验证码错误次数过多，请重新获取验证码"),

    CODE_EXPIRED(40160, "验证码已过期，请重新获取"),

    IP_ABNORMAL(40170, "登录IP异常，请进行邮箱验证码验证"),

    DEMO_DENY(49000, "演示模式，禁止写操作");
    private final Integer code;
    private final String msg;

}
