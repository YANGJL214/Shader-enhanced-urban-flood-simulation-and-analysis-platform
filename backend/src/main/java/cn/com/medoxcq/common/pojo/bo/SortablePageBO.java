package cn.com.medoxcq.common.pojo.bo;

import cn.com.medoxcq.common.pojo.SortingField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Schema(description = "可排序的分页参数")
@Getter
@Setter
public class SortablePageBO extends PageBO {

    @Schema(description = "排序字段")
    private List<SortingField> sortingFields;

}
