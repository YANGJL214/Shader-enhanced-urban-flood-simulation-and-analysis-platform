package cn.com.medoxcq.common.pojo;

import cn.com.medoxcq.common.exception.BusinessException;
import cn.com.medoxcq.common.exception.enums.ErrorCodeEnum;
import cn.hutool.core.lang.Assert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Getter
@Setter
@Schema(description = "统一响应消息报文")
public class CommonResult<T> implements Serializable {
    @Schema(description = "成功标识")
    private Boolean success;

    /**
     *
     * @see ErrorCodeEnum#getCode()
     */
    @Schema(description = "错误码")
    private Integer code;
    @Schema(description = "返回数据")
    private T data;

    /**
     *
     * @see ErrorCodeEnum#getMsg() ()
     */
    @Schema(description = "错误提示")
    private String msg;

    /**
     * <p>
     *
     */
    public static <T> CommonResult<T> fail(CommonResult<?> result) {
        return fail(result.getCode(), result.getMsg());
    }

    public static <T> CommonResult<T> fail(Integer code, String message) {
        Assert.isTrue(!ErrorCodeEnum.SUCCESS.getCode().equals(code), "code 必须是错误的！");
        CommonResult<T> result = new CommonResult<>();
        result.success = Boolean.FALSE;
        result.code = code;
        result.msg = message;
        return result;
    }

    public static <T> CommonResult<T> fail(ErrorCodeEnum errorCodeEnum) {
        return fail(errorCodeEnum.getCode(), errorCodeEnum.getMsg());
    }

    public static <T> CommonResult<T> fail() {
        return fail(ErrorCodeEnum.BUSINESS_ERROR);
    }

    public static <T> CommonResult<T> fail(String message) {
        return fail(ErrorCodeEnum.BUSINESS_ERROR.getCode(), message);
    }

    public static <T> CommonResult<T> success() {
        return success(ErrorCodeEnum.SUCCESS.getMsg());
    }

    public static <T> CommonResult<T> success(String message) {
        CommonResult<T> result = new CommonResult<>();
        result.success = Boolean.TRUE;
        result.code = ErrorCodeEnum.SUCCESS.getCode();
        result.msg = message;
        return result;
    }

    public static <T> CommonResult<T> data(T data) {
        return data(data, ErrorCodeEnum.SUCCESS.getMsg());
    }

    public static <T> CommonResult<T> data(T data, String message) {
        return data(ErrorCodeEnum.SUCCESS.getCode(), data, message);
    }

    public static <T> CommonResult<T> data(Integer code, T data, String message) {
        CommonResult<T> result = new CommonResult<>();
        result.success = Boolean.TRUE;
        result.code = code;
        result.data = data;
        result.msg = message;
        return result;
    }

    public static <T> CommonResult<T> fail(BusinessException serviceException) {
        return fail(serviceException.getCode(), serviceException.getMessage());
    }

}
