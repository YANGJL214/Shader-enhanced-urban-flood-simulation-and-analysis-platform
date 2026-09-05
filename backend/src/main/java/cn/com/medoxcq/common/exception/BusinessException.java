package cn.com.medoxcq.common.exception;

import cn.com.medoxcq.common.exception.enums.ErrorCodeEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class BusinessException extends RuntimeException {

    /**
     *
     * @see ErrorCodeEnum
     */
    private Integer code;
    private String message;

    public BusinessException(String message) {
        this.code = ErrorCodeEnum.BUSINESS_ERROR.getCode();
        this.message = message;
    }

    public BusinessException(ErrorCodeEnum errorCodeEnum) {
        this.code = errorCodeEnum.getCode();
        this.message = errorCodeEnum.getMsg();
    }

}
