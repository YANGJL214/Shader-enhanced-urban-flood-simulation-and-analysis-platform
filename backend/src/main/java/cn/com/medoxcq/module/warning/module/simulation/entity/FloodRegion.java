package cn.com.medoxcq.module.warning.module.simulation.entity;

import cn.com.medoxcq.common.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;

/**
 *
 * @author system
 * @since 2025/12/11
 */
@Getter
@Setter
@FieldNameConstants
@TableName(value = "flood_region", autoResultMap = true)
public class FloodRegion extends BaseDO {
    @TableId("id")
    private String id;
    @TableField("region_name")
    private String regionName;
    @TableField("description")
    private String description;
    @TableField("data_path")
    private String dataPath;
    @TableField("tin_file_path")
    private String tinFilePath;
    @TableField("dem_file_path")
    private String demFilePath;
    @TableField("node_shp_path")
    private String nodeShpPath;
    @TableField("link_shp_path")
    private String linkShpPath;
    @TableField("landuse_file_path")
    private String landuseFilePath;
    @TableField("config_params")
    private String configParams;
    @TableField("init_status")
    private Integer initStatus;
    @TableField("error_message")
    private String errorMessage;
    @TableField(value = "creator", fill = FieldFill.INSERT)
    private String createBy;
    @TableField(value = "updater", fill = FieldFill.INSERT_UPDATE)
    private String updateBy;
    @TableField(value = "deleted", fill = FieldFill.INSERT)
    private String delFlag;
}
