package cn.com.medoxcq.common.pojo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 *
 * @author lijy
 * @since 2024/11/08
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SortingField implements Serializable {
    public static final String ORDER_ASC = "asc";
    public static final String ORDER_DESC = "desc";
    private String field;
    private String order;

}
