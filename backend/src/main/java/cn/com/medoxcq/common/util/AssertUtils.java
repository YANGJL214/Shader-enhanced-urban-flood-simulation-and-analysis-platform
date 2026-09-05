package cn.com.medoxcq.common.util;

import cn.com.medoxcq.common.exception.BusinessException;
import cn.com.medoxcq.common.exception.enums.ErrorCodeEnum;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
public class AssertUtils {

    public static void isTrue(boolean expression, String message) {
        if (expression) {
            throw new BusinessException(ErrorCodeEnum.BUSINESS_ERROR.getCode(), message);
        }
    }

    public static void isTrue(boolean expression, Integer code, String message) {
        if (expression) {
            throw new BusinessException(code, message);
        }
    }

    public static void isTrue(boolean expression, ErrorCodeEnum errorCodeEnum) {
        if (expression) {
            throw new BusinessException(errorCodeEnum);
        }
    }

}
