package cn.com.medoxcq.common.util;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 *
 * @author lijy
 * @since 2024/11/08
 */
public class BeanUtils {

    public static void copyProperties(Object source, Object target) {
        BeanUtil.copyProperties(source, target);
    }

    public static <T> T copyProperties(Object source, Class<T> targetClass) {
        return BeanUtil.toBean(source, targetClass);
    }

    public static <T> T copyProperties(Object source, Class<T> targetClass, boolean ignoreNullValue) {
        if (ignoreNullValue) {
            return BeanUtil.toBean(source, targetClass, CopyOptions.create().ignoreNullValue());
        }
        return copyProperties(source, targetClass);
    }

    public static <T> T copyProperties(Object source, Class<T> targetClass, String... ignoreProperties) {
        return BeanUtil.copyProperties(source, targetClass, ignoreProperties);
    }

    public static <S> List<Map<String, Object>> copyToMapList(Collection<S> source) {
        if (source == null) {
            return null;
        } else if (source.isEmpty()) {
            return Collections.emptyList();
        } else {
            return source.stream().map(BeanUtil::beanToMap).collect(Collectors.toList());
        }
    }

    public static <S, T> List<T> copyToList(Collection<S> source, Class<T> targetType) {
        return BeanUtil.copyToList(source, targetType);
    }

    public static <S, T> List<T> copyToList(Collection<S> source, Class<T> targetType, boolean ignoreNullValue) {
        if (ignoreNullValue) {
            return BeanUtil.copyToList(source, targetType, CopyOptions.create().ignoreNullValue());
        }
        return copyToList(source, targetType);
    }

    public static <S, T> List<T> copyToList(Collection<S> source, Class<T> targetType, String... ignoreProperties) {
        return BeanUtil.copyToList(source, targetType, CopyOptions.create().setIgnoreProperties(ignoreProperties));
    }

}