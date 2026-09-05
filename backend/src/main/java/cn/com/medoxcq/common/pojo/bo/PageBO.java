package cn.com.medoxcq.common.pojo.bo;

import cn.com.medoxcq.common.constant.PageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Schema(description = "分页参数")
@Getter
@Setter
public class PageBO implements Serializable {

    private static final long serialVersionUID = 1L;
    @Schema(description = "每页条数，最大值为 1000", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    @NotNull(message = "每页条数不能为空")
    @Min(value = 1, message = "每页条数最小值为 1")
    @Max(value = 1000, message = "每页条数最大值为 1000")
    private Long size = PageConstants.DEFAULT_SIZE;
    @Schema(description = "页码，从 1 开始", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码最小值为 1")
    private Long current = PageConstants.DEFAULT_CURRENT;

}
