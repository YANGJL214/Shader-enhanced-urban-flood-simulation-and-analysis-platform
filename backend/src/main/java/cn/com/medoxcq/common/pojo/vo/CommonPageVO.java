package cn.com.medoxcq.common.pojo.vo;

import cn.com.medoxcq.common.constant.PageConstants;
import cn.com.medoxcq.common.pojo.bo.PageBO;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.Accessors;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Schema(description = "分页通用返回对象")
public class CommonPageVO<T> implements Serializable {

    private static final long serialVersionUID = -467171717336145704L;
    @Schema(description = "列表数据")
    private List<T> records;
    @Schema(description = "总数")
    private long total;
    @Schema(description = "每页条数")
    private long size;
    @Schema(description = "当前页")
    private long current;

    public CommonPageVO<T> setRecords(List<T> records) {
        this.records = records;
        return this;
    }

    /**
     *
     */
    @SuppressWarnings("unchecked")
    public <R> CommonPageVO<R> convert(Function<? super T, ? extends R> mapper) {
        List collect = this.getRecords().stream().map(mapper).collect(Collectors.toList());
        return this.setRecords(collect);
    }

    /**
     *
     */
    public static <T> CommonPageVO<T> build(List<T> records, @NotNull PageBO pageBO) {
        return build(records, pageBO.getCurrent(), pageBO.getSize());
    }

    /**
     *
     */
    public static <T> CommonPageVO<T> build(List<T> records, @NotNull PageBO pageBO, long total) {
        return build(records, pageBO.getCurrent(), pageBO.getSize(), total);
    }

    /**
     *
     */
    public static <T> CommonPageVO<T> build(List<T> records, long current, long size) {
        return build(records, current, size, records.size());
    }

    /**
     *
     */
    public static <T> CommonPageVO<T> build(List<T> records, long current, long size, long total) {
        if (size == PageConstants.SIZE_NONE) {
            return CommonPageVO.<T>builder()
                    .size(PageConstants.SIZE_NONE)
                    .current(PageConstants.DEFAULT_CURRENT)
                    .records(records)
                    .total(total)
                    .build();
        }
        int pageNo = NumberUtil.max((int) current, 1);
        int pageSize = NumberUtil.max((int) size, 0);
        return CommonPageVO.<T>builder()
                .size(size)
                .current(current)
                .records(CollUtil.sub(records, (pageNo - 1) * pageSize, pageNo * pageSize))
                .total(total)
                .build();
    }

    /**
     *
     */
    public static <T> CommonPageVO<T> empty(PageBO pageBO) {
        return empty(pageBO, 0);
    }

    /**
     *
     */
    public static <T> CommonPageVO<T> empty(PageBO pageBO, long total) {
        return CommonPageVO.<T>builder()
                .total(total)
                .size(pageBO.getSize())
                .current(pageBO.getCurrent())
                .records(Collections.emptyList())
                .build();
    }

}
