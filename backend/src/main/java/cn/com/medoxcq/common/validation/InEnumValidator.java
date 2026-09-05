package cn.com.medoxcq.common.validation;

import cn.com.medoxcq.common.enums.BaseEnum;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.Objects;

/** Validates integer values against the codes exposed by a BaseEnum. */
public class InEnumValidator implements ConstraintValidator<InEnum, Integer> {

    private Class<? extends BaseEnum> enumClass;

    @Override
    public void initialize(InEnum annotation) {
        this.enumClass = annotation.enumClass();
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return false;
        }
        for (Object constant : constants) {
            if (Objects.equals(((BaseEnum) constant).getCode(), value)) {
                return true;
            }
        }
        return false;
    }
}
