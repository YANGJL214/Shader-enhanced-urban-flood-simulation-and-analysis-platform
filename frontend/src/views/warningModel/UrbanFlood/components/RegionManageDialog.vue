<template>
  <el-dialog v-model="dialogVisible" :title="title || t('region.title')" width="900px" :close-on-click-modal="false" @close="handleClose">
    <div class="region-manage-container">
      <div class="operation-bar">
        <el-button type="primary" @click="showAddDialog">{{ t('region.addRegion') }}</el-button>
        <el-button @click="refreshRegionList">{{ t('common.refresh') }}</el-button>
      </div>

      <el-table :data="regionList" style="width: 100%" v-loading="loading">
        <el-table-column prop="regionName" :label="t('region.regionName')" width="150" />
        <el-table-column prop="description" :label="t('common.description')" width="200" show-overflow-tooltip />
        <el-table-column prop="initStatus" :label="t('region.initStatus')" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.initStatus === 0" type="info">{{ t('region.uninitialized') }}</el-tag>
            <el-tag v-else-if="row.initStatus === 1" type="warning">{{ t('region.initializing') }}</el-tag>
            <el-tag v-else-if="row.initStatus === 2" type="success">{{ t('region.completed') }}</el-tag>
            <el-tag v-else-if="row.initStatus === 3" type="danger">{{ t('region.initFailed') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" :label="t('common.createTime')" width="180" />
        <el-table-column :label="t('common.operation')" width="150">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewDetail(row)">{{ t('common.details') }}</el-button>
            <el-button link type="danger" size="small" @click="deleteRegion(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="addDialogVisible" :title="t('region.initDialog')" width="1100px" :close-on-click-modal="false"
      append-to-body @close="handleAddDialogClose">
      <div style="max-height: 70vh; overflow-y: auto;">
        <el-form :model="regionForm" :rules="regionRules" ref="regionFormRef" label-width="150px">
          <el-divider content-position="left">{{ t('region.basicInfo') }}</el-divider>
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item :label="t('region.regionName')" prop="regionName">
                <el-input v-model="regionForm.regionName" :placeholder="t('region.inputRegionName')" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('region.regionDescription')" prop="description">
                <el-input v-model="regionForm.description" :placeholder="t('region.inputRegionDescription')" />
              </el-form-item>
            </el-col>
          </el-row>

         

          <el-divider content-position="left">{{ t('region.fileUpload') }}</el-divider>
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item :label="t('region.nodeShapefile')" prop="nodeShpFiles">
                <el-upload ref="nodeUploadRef" :auto-upload="false" :on-change="handleNodeFileChange" 
                  :file-list="nodeFileList" multiple accept=".shp,.dbf,.shx,.prj">
                  <el-button size="small">{{ t('region.selectNodeFiles') }}</el-button>
                  <template #tip>
                    <div class="el-upload__tip">{{ t('region.shapefileTip') }}</div>
                  </template>
                </el-upload>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('region.linkShapefile')" prop="linkShpFiles">
                <el-upload ref="linkUploadRef" :auto-upload="false" :on-change="handleLinkFileChange" 
                  :file-list="linkFileList" multiple accept=".shp,.dbf,.shx,.prj">
                  <el-button size="small">{{ t('region.selectLinkFiles') }}</el-button>
                  <template #tip>
                    <div class="el-upload__tip">{{ t('region.shapefileTip') }}</div>
                  </template>
                </el-upload>
              </el-form-item>
            </el-col>
          </el-row>
          
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item :label="t('region.shpFieldMapping')">
                <el-button type="primary" size="small" @click="openMappingDialog" :icon="'Setting'">
                  {{ t('region.configureShpMapping') }}
                </el-button>
                <div style="font-size: 12px; color: #909399; margin-top: 4px;">
                  {{ t('region.mappingHint') }}
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('region.demFile')" prop="demFiles">
                <el-upload ref="demUploadRef" :auto-upload="false" :on-change="handleDemFileChange" 
                  :file-list="demFileList" multiple accept=".asc,.prj">
                  <el-button size="small">{{ t('region.selectDem') }}</el-button>
                  <template #tip>
                    <div class="el-upload__tip">{{ t('region.demTip') }}</div>
                  </template>
                </el-upload>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('region.landuseFile')">
                <el-upload ref="landuseUploadRef" :auto-upload="false" :on-change="handleLanduseFileChange"
                  :file-list="landuseFileList" multiple accept=".asc,.prj">
                  <el-button size="small">{{ t('region.selectLanduse') }}</el-button>
                  <template #tip>
                    <div class="el-upload__tip">{{ t('region.demTip') }}</div>
                  </template>
                </el-upload>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('region.landuseMapping')">
                <el-button type="primary" size="small" @click="openLanduseMappingDialog" 
                  :icon="landuseConfigured ? 'Check' : 'Setting'">
                  {{ landuseConfigured ? t('region.mappingConfigured') : t('region.configureLanduse') }}
                  <span v-if="landuseMappings.length > 0" style="margin-left: 5px;">
                    ({{ t('region.itemCount', { count: landuseMappings.length }) }})
                  </span>
                </el-button>            
                <div style="font-size: 12px; color: #909399; margin-top: 4px;">
                  {{ t('region.landuseMappingHint') }}
                </div>
              </el-form-item>
            </el-col>
          </el-row>
          <el-divider content-position="left">{{ t('region.tinConfig') }}</el-divider>
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item :label="t('region.gridType')" prop="tinGridType">
                <el-radio-group v-model="regionForm.tinGridType">
                  <el-radio label="adaptive">{{ t('region.adaptiveGrid') }}</el-radio>
                  <el-radio label="regular">{{ t('region.regularGrid') }}</el-radio>
                </el-radio-group>
                <div style="font-size: 12px; color: #909399; margin-top: 4px;">
                  {{ t('region.gridTypeHint') }}
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="12" v-if="regionForm.tinGridType === 'regular'">
              <el-form-item :label="t('region.gridSize')" prop="regularGridSize">
                <el-input-number v-model="regionForm.regularGridSize" :min="1" :max="100" :step="1" 
                  style="width: 100%;" />
                <div style="font-size: 12px; color: #909399; margin-top: 4px;">
                  {{ t('region.gridSizeHint') }}
                </div>
              </el-form-item>
            </el-col>
            <template v-if="regionForm.tinGridType === 'adaptive'">
              <el-col :span="12">
                <el-form-item :label="t('region.maxTriangleArea')" prop="adaptiveMaxTriangleArea">
                  <el-input-number v-model="regionForm.adaptiveMaxTriangleArea" :min="1" :step="10" :precision="2"
                    style="width: 100%;" />
                  <div style="font-size: 12px; color: #909399; margin-top: 4px;">
                    {{ t('region.maxTriangleHint') }}
                  </div>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item :label="t('region.minAngle')" prop="adaptiveMinAngle">
                  <el-input-number v-model="regionForm.adaptiveMinAngle" :min="1" :max="33" :step="1" :precision="1"
                    style="width: 100%;" />
                  <div style="font-size: 12px; color: #909399; margin-top: 4px;">
                    {{ t('region.minAngleHint') }}
                  </div>
                </el-form-item>
              </el-col>
              <el-col :span="24">
                <el-form-item :label="t('region.constraintShp')">
                  <div class="adaptive-shp-panel">
                    <div class="adaptive-shp-toolbar">
                      <span>{{ t('region.constraintHint') }}</span>
                      <el-button size="small" type="primary" icon="Plus" @click="addAdaptiveMeshFeature">
                        {{ t('region.addFeature') }}
                      </el-button>
                    </div>
                    <el-empty v-if="adaptiveMeshFeatures.length === 0" :description="t('region.noFeature')"
                      :image-size="80" />
                    <div v-else class="adaptive-shp-list">
                      <div v-for="(item, index) in adaptiveMeshFeatures" :key="item.id" class="adaptive-shp-item">
                        <el-row :gutter="12" align="middle">
                          <el-col :span="6">
                            <div class="field-caption">{{ t('region.featureType') }}</div>
                            <el-select v-model="item.featureType" style="width: 100%;"
                              @change="handleAdaptiveFeatureTypeChange(item)">
                              <el-option :label="t('region.buildingHole')" value="building" />
                              <el-option :label="t('region.roadConstraint')" value="road" />
                              <el-option :label="t('region.otherHole')" value="hole_polygon" />
                              <el-option :label="t('region.otherConstraint')" value="constraint" />
                            </el-select>
                          </el-col>
                          <el-col :span="6">
                            <div class="field-caption">{{ t('region.processMode') }}</div>
                            <el-select v-model="item.meshAction" style="width: 100%;">
                              <el-option :label="t('region.hole')" value="hole" />
                              <el-option :label="t('region.edgeConstraint')" value="constraint" />
                            </el-select>
                          </el-col>
                          <el-col :span="10">
                            <div class="field-caption">{{ t('region.shapefileFiles') }}</div>
                            <el-upload :auto-upload="false"
                              :on-change="(file, fileList) => handleAdaptiveMeshFileChange(index, fileList)"
                              :on-remove="(file, fileList) => handleAdaptiveMeshFileChange(index, fileList)"
                              :file-list="item.fileList" multiple accept=".shp,.dbf,.shx,.prj">
                              <el-button size="small">{{ t('region.selectShp') }}</el-button>
                              <template #tip>
                                <div class="el-upload__tip">{{ t('region.sameShapefileTip') }}</div>
                              </template>
                            </el-upload>
                          </el-col>
                          <el-col :span="2" style="text-align: center;">
                            <el-button type="danger" icon="Delete" circle @click="removeAdaptiveMeshFeature(index)" />
                          </el-col>
                        </el-row>
                      </div>
                    </div>
                  </div>
                </el-form-item>
              </el-col>
            </template>
          </el-row>    
          <el-divider content-position="left">{{ t('region.physicalParameters') }}</el-divider>
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item :label="t('region.manningsN')" prop="manningsN">
                <el-input-number v-model="regionForm.manningsN" :min="0.01" :max="1" :step="0.001" :precision="3" 
                  style="width: 100%;" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('region.sediment')" prop="sedimentConcentration">
                <el-input-number v-model="regionForm.sedimentConcentration" :min="0" :step="0.1" :precision="2" 
                  style="width: 100%;" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item :label="t('region.stemDiameter')" prop="vegetationStemDiameter">
                <el-input-number v-model="regionForm.vegetationStemDiameter" :min="0" :step="0.001" :precision="3" 
                  style="width: 100%;" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('region.stemSpacing')" prop="vegetationStemSpacing">
                <el-input-number v-model="regionForm.vegetationStemSpacing" :min="0" :step="0.1" :precision="2" 
                  style="width: 100%;" />
              </el-form-item>
            </el-col>
          </el-row>

        </el-form>
      </div>
      <template #footer>
        <el-button @click="addDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitRegion" :loading="submitting">{{ t('region.submitInitialization') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="landuseMappingDialogVisible" :title="t('region.landuseHydraulicTitle')" width="1050px"
      :close-on-click-modal="false" append-to-body>
      <div style="margin-bottom: 16px;">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            {{ t('region.landuseInstructions') }}
          </template>
        </el-alert>
      </div>

      <el-row :gutter="16" style="margin-bottom: 16px;">
        <el-col :span="8">
          <el-switch v-model="infiltrationEnabled" :active-text="t('region.enableHorton')" />
        </el-col>
        <el-col :span="10">
          <el-switch
            v-model="infiltrationStrictClasses"
            :active-text="t('region.strictLanduse')"
            :disabled="!infiltrationEnabled"
          />
        </el-col>
      </el-row>
      
      <div style="margin-bottom: 16px; display: flex; justify-content: space-between; align-items: center;">
        <span style="font-weight: 500; font-size: 14px;">{{ t('region.typeMappingList') }}</span>
        <el-button type="primary" size="small" icon="Plus" @click="addLanduseMapping">
          {{ t('region.addMapping') }}
        </el-button>
      </div>

      <div style="max-height: 400px; overflow-y: auto; margin-bottom: 16px;">
        <el-empty v-if="landuseMappings.length === 0" :description="t('region.noMapping')" 
          :image-size="100" />
        <div v-else>
          <div v-for="(item, index) in landuseMappings" :key="index" 
            style="margin-bottom: 12px; padding: 12px; background: #f5f7fa; border-radius: 4px;">
            <el-row :gutter="10" align="middle">
              <el-col :span="6">
                <div style="margin-bottom: 4px; font-size: 12px; color: #909399;">{{ t('region.classId') }}</div>
                <el-input-number v-model="item.classId" :placeholder="t('region.inputClassId')" 
                  :min="0" :step="1" :precision="0" style="width: 100%;" 
                  controls-position="right" />
              </el-col>
              <el-col :span="6">
                <div style="margin-bottom: 4px; font-size: 12px; color: #909399;">{{ t('region.manning') }}</div>
                <el-input-number v-model="item.manning" :placeholder="t('region.inputManning')" 
                  :min="0.001" :max="1" :step="0.001" :precision="3" 
                  style="width: 100%;" controls-position="right" />
              </el-col>
              <el-col :span="12">
                <div style="margin-bottom: 4px; font-size: 12px; color: #909399;">{{ t('region.infiltrationUnits') }}</div>
                <span style="font-size: 12px; color: #606266;">{{ t('region.infiltrationUnitsHint') }}</span>
              </el-col>
            </el-row>
            <el-row v-if="infiltrationEnabled" :gutter="10" class="landuse-infiltration-row">
              <el-col :span="5">
                <div class="field-caption">{{ t('region.maxInfiltration') }}</div>
                <el-input-number v-model="item.maxRate" :min="0" :step="1" :precision="2"
                  style="width: 100%;" controls-position="right" />
              </el-col>
              <el-col :span="5">
                <div class="field-caption">{{ t('region.minInfiltration') }}</div>
                <el-input-number v-model="item.minRate" :min="0" :step="1" :precision="2"
                  style="width: 100%;" controls-position="right" />
              </el-col>
              <el-col :span="4">
                <div class="field-caption">{{ t('region.decay') }}</div>
                <el-input-number v-model="item.decay" :min="0" :step="0.1" :precision="3"
                  style="width: 100%;" controls-position="right" />
              </el-col>
              <el-col :span="5">
                <div class="field-caption">{{ t('region.dryTime') }}</div>
                <el-input-number v-model="item.dryingTimeDays" :min="0" :step="1" :precision="2"
                  style="width: 100%;" controls-position="right" />
              </el-col>
              <el-col :span="5">
                <div class="field-caption">{{ t('region.maxVolume') }}</div>
                <el-input-number v-model="item.maxInfiltration" :min="0" :step="1" :precision="2"
                  style="width: 100%;" controls-position="right" />
              </el-col>
            </el-row>
            <div style="margin-top: 10px; text-align: right;">
              <el-button type="danger" icon="Delete" circle
                @click="removeLanduseMapping(index)"
                :title="t('region.deleteMapping')" />
            </div>
          </div>
        </div>
      </div>

      <el-divider />

      <div style="background: #fff8e6; padding: 12px; border-radius: 4px; border: 1px solid #ffd666;">
        <el-row :gutter="10" align="middle">
          <el-col :span="6">
            <div style="font-weight: 500; color: #606266;">{{ t('region.defaultManning') }}</div>
          </el-col>
          <el-col :span="6">
            <el-input-number v-model="landuseDefaultManning" 
              :min="0.001" :max="1" :step="0.001" :precision="3" 
              style="width: 100%;" controls-position="right" />
          </el-col>
          <el-col :span="12">
            <div style="font-size: 12px; color: #8c8c8c;">
              {{ t('region.defaultManningHint') }}
            </div>
          </el-col>
        </el-row>
        <el-row v-if="infiltrationEnabled" :gutter="10" class="landuse-infiltration-row">
          <el-col :span="5">
            <div class="field-caption">{{ t('region.defaultMaxInfiltration') }}</div>
            <el-input-number v-model="landuseDefaultInfiltration.maxRate" :min="0" :step="1" :precision="2"
              style="width: 100%;" controls-position="right" />
          </el-col>
          <el-col :span="5">
            <div class="field-caption">{{ t('region.defaultMinInfiltration') }}</div>
            <el-input-number v-model="landuseDefaultInfiltration.minRate" :min="0" :step="1" :precision="2"
              style="width: 100%;" controls-position="right" />
          </el-col>
          <el-col :span="4">
            <div class="field-caption">{{ t('region.defaultDecay') }}</div>
            <el-input-number v-model="landuseDefaultInfiltration.decay" :min="0" :step="0.1" :precision="3"
              style="width: 100%;" controls-position="right" />
          </el-col>
          <el-col :span="5">
            <div class="field-caption">{{ t('region.defaultDryTime') }}</div>
            <el-input-number v-model="landuseDefaultInfiltration.dryingTimeDays" :min="0" :step="1" :precision="2"
              style="width: 100%;" controls-position="right" />
          </el-col>
          <el-col :span="5">
            <div class="field-caption">{{ t('region.defaultMaxVolume') }}</div>
            <el-input-number v-model="landuseDefaultInfiltration.maxInfiltration" :min="0" :step="1" :precision="2"
              style="width: 100%;" controls-position="right" />
          </el-col>
        </el-row>
      </div>

      <template #footer>
        <el-button @click="cancelLanduseMappingConfig">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="confirmLanduseMappingConfig">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mappingDialogVisible" :title="t('region.shapefileMappingTitle')" width="820px"
      :close-on-click-modal="false" append-to-body>
      <div style="margin-bottom: 12px;">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            {{ t('region.shapefileMappingInstructions') }}
          </template>
        </el-alert>
      </div>

      <div style="display: flex; justify-content: flex-end; gap: 8px; margin-bottom: 12px;">
        <el-button size="small" type="primary" plain @click="downloadMappingConfig">{{ t('region.downloadConfig') }}</el-button>
        <el-button size="small" plain @click="triggerUploadMappingConfig">{{ t('region.uploadConfig') }}</el-button>
        <input ref="mappingConfigFileInput" type="file" accept=".txt" style="display: none;" @change="handleMappingConfigUpload" />
      </div>

      <el-divider content-position="left">{{ t('region.nodeFieldMapping') }}</el-divider>
      <el-row :gutter="12">
        <el-col :span="12" v-for="(label, key) in nodeFieldLabels" :key="key" style="margin-bottom: 12px;">
          <div style="font-size: 12px; color: #909399; margin-bottom: 4px;">{{ label }}</div>
          <el-input v-model="nodeFieldMapping[key]" :placeholder="t('region.fieldPlaceholder', { defaultValue: nodeFieldDefaults[key] })" />
        </el-col>
      </el-row>

      <el-divider content-position="left">{{ t('region.linkFieldMapping') }}</el-divider>
      <el-row :gutter="12">
        <el-col :span="12" v-for="(label, key) in linkFieldLabels" :key="key" style="margin-bottom: 12px;">
          <div style="font-size: 12px; color: #909399; margin-bottom: 4px;">{{ label }}</div>
          <el-input v-model="linkFieldMapping[key]" :placeholder="t('region.fieldPlaceholder', { defaultValue: linkFieldDefaults[key] })" />
        </el-col>
      </el-row>

      <el-divider content-position="left">{{ t('region.nodeTypeMapping') }}</el-divider>
      <div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
        <span style="color: #606266;">{{ t('region.nodeTypeInstructions') }}</span>
        <el-button size="small" type="primary" icon="Plus" @click="addNodeTypeMapping">{{ t('common.add') }}</el-button>
      </div>
      <div style="max-height: 200px; overflow-y: auto; margin-bottom: 12px;">
        <el-empty v-if="nodeTypeMappingList.length === 0" :description="t('region.noMappingAdd')" />
        <div v-else>
          <el-row v-for="(item, index) in nodeTypeMappingList" :key="index" :gutter="8" style="margin-bottom: 8px;">
            <el-col :span="10">
              <el-select v-model="item.target" :placeholder="t('region.selectNodeType')" style="width: 100%;">
                <el-option :label="t('region.inspectionWell')" value="JUNCTION_CHECK" />
                <el-option :label="t('region.connectionNode')" value="JUNCTION_CONN" />
                <el-option :label="t('region.outfall')" value="OUTFALL" />
                <el-option :label="t('region.inlet')" value="INLET" />
              </el-select>
            </el-col>
            <el-col :span="10">
              <el-input v-model="item.source" :placeholder="t('region.attributeValue')" />
            </el-col>
            <el-col :span="4" style="text-align: center;">
              <el-button circle type="danger" icon="Delete" @click="removeNodeTypeMapping(index)" />
            </el-col>
          </el-row>
        </div>
      </div>

      <el-divider content-position="left">{{ t('region.shapeMapping') }}</el-divider>
      <div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
        <span style="color: #606266;">{{ t('region.shapeInstructions') }}</span>
        <el-button size="small" type="primary" icon="Plus" @click="addShapeTypeMapping">{{ t('common.add') }}</el-button>
      </div>
      <div style="max-height: 200px; overflow-y: auto;">
        <el-empty v-if="shapeTypeMappingList.length === 0" :description="t('region.noMappingAdd')" />
        <div v-else>
          <el-row v-for="(item, index) in shapeTypeMappingList" :key="index" :gutter="8" style="margin-bottom: 8px;">
            <el-col :span="10">
              <el-select v-model="item.target" :placeholder="t('region.selectShape')" style="width: 100%;">
                <el-option :label="t('region.circularPipe')" value="CIRCULAR" />
                <el-option :label="t('region.rectClosedPipe')" value="RECT_CLOSED" />
                <el-option :label="t('region.rectOpenPipe')" value="RECT_OPEN" />
              </el-select>
            </el-col>
            <el-col :span="10">
              <el-input v-model="item.source" :placeholder="t('region.shapeAttributeValue')" />
            </el-col>
            <el-col :span="4" style="text-align: center;">
              <el-button circle type="danger" icon="Delete" @click="removeShapeTypeMapping(index)" />
            </el-col>
          </el-row>
        </div>
      </div>

      <template #footer>
        <el-button @click="cancelMappingConfig">{{ t('common.cancel') }}</el-button>
        <el-button @click="resetMappingToDefault">{{ t('common.restoreDefault') }}</el-button>
        <el-button type="primary" @click="confirmMappingConfig">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { ref, computed, getCurrentInstance, onMounted } from 'vue';
import { ensureSessionId } from '@/utils/public-session';
import { useI18n } from '@/i18n';

const { proxy } = getCurrentInstance();
const { API, MxMsg, MxMsgBox } = proxy;
const { locale, t } = useI18n();

const props = defineProps({
  publicMode: {
    type: Boolean,
    default: false,
  },
  title: {
    type: String,
    default: ''
  }
});

const dialogVisible = ref(false);
const addDialogVisible = ref(false);
const landuseMappingDialogVisible = ref(false);
const mappingDialogVisible = ref(false);
const loading = ref(false);
const submitting = ref(false);
const regionList = ref([]);
const landuseConfigured = ref(false);

const regionFormRef = ref(null);
const createDefaultNodeFieldMapping = () => ({
  name: 'Name',
  elevation: 'Elevation',
  node_type: 'Type',
  maxdepth: 'maxDepth',
  initdepth: 'InitDepth',
  surdepth: 'SurDepth',
  aponded: 'APonded'
});

const createDefaultLinkFieldMapping = () => ({
  name: 'Name',
  from_node: 'FromNode',
  to_node: 'ToNode',
  length: 'Length',
  material: 'Material',
  roughness: 'Roughness',
  shape: 'XsectShape',
  geom1: 'Geom1',
  geom2: 'Geom2'
});

const createDefaultNodeTypeMapping = () => ([
  { source: '检查井', target: 'JUNCTION_CHECK' },
  { source: '出水口', target: 'OUTFALL' },
  { source: '连接点', target: 'JUNCTION_CONN' },
  { source: '入水口', target: 'INLET' }
]);

const createDefaultShapeTypeMapping = () => ([
  { source: '圆形', target: 'CIRCULAR' },
  { source: '矩形', target: 'RECT_CLOSED' }
]);

const nodeFieldLabelKeys = {
  name: 'region.nodeName', elevation: 'region.elevation', node_type: 'region.nodeType', maxdepth: 'region.maxDepth',
  initdepth: 'region.initialDepth', surdepth: 'region.surchargeDepth', aponded: 'region.pondedArea'
};
const linkFieldLabelKeys = {
  name: 'region.linkName', from_node: 'region.fromNode', to_node: 'region.toNode', length: 'region.linkLength',
  material: 'region.material', roughness: 'region.roughness', shape: 'region.shapeType', geom1: 'region.geom1', geom2: 'region.geom2'
};
const nodeFieldLabels = computed(() => Object.fromEntries(Object.entries(nodeFieldLabelKeys).map(([key, labelKey]) => [key, t(labelKey)])));
const linkFieldLabels = computed(() => Object.fromEntries(Object.entries(linkFieldLabelKeys).map(([key, labelKey]) => [key, t(labelKey)])));

const nodeLabelToKey = {
  '节点名称': 'name', 'Node Name': 'name', '高程': 'elevation', 'Elevation': 'elevation', '节点类型': 'node_type', 'Node Type': 'node_type',
  '最大深度': 'maxdepth', 'Maximum Depth': 'maxdepth', '初始水深': 'initdepth', 'Initial Depth': 'initdepth',
  '超标高': 'surdepth', 'Surcharge Depth': 'surdepth', '积水面积': 'aponded', 'Ponded Area': 'aponded'
};
const linkLabelToKey = {
  '管线名称': 'name', 'Link Name': 'name', '起点节点': 'from_node', 'From Node': 'from_node', '终点节点': 'to_node', 'To Node': 'to_node',
  '管线长度': 'length', 'Link Length': 'length', '材质': 'material', 'Material': 'material', '粗糙度': 'roughness', 'Roughness': 'roughness',
  '断面类型': 'shape', 'Cross-section Type': 'shape', '直径（圆形管道）/宽度（矩形管道）': 'geom1',
  'Diameter (circular) / Width (rectangular)': 'geom1', '高度（矩形管道）': 'geom2', 'Height (rectangular)': 'geom2'
};

const nodeFieldDefaults = createDefaultNodeFieldMapping();
const linkFieldDefaults = createDefaultLinkFieldMapping();

const nodeTypeDisplay = computed(() => ({
  JUNCTION_CHECK: t('region.inspectionWell'), JUNCTION_CONN: t('region.connectionNode'),
  OUTFALL: t('region.outfall'), INLET: t('region.inlet')
}));
const nodeTypeCodeFromLabel = {
  '检查井': 'JUNCTION_CHECK', 'Inspection Well': 'JUNCTION_CHECK', '连接点': 'JUNCTION_CONN', 'Connection Node': 'JUNCTION_CONN',
  '排水口': 'OUTFALL', '出水口': 'OUTFALL', 'Outfall': 'OUTFALL', '入水口': 'INLET', 'Inlet': 'INLET'
};

const shapeTypeDisplay = computed(() => ({
  CIRCULAR: t('region.circular'), RECT_CLOSED: t('region.rectClosed'), RECT_OPEN: t('region.rectOpen')
}));
const shapeTypeCodeFromLabel = {
  '圆形': 'CIRCULAR', 'Circular': 'CIRCULAR', '矩形封闭': 'RECT_CLOSED', 'Closed Rectangular': 'RECT_CLOSED',
  '矩形开放': 'RECT_OPEN', 'Open Rectangular': 'RECT_OPEN'
};

const regionForm = ref({
  regionName: '',
  description: '',
  manningsN: 0.03,
  sedimentConcentration: 0.0,
  vegetationStemDiameter: 0.01,
  vegetationStemSpacing: 1.0,
  tinGridType: 'adaptive',
  regularGridSize: 10.0,
  adaptiveMaxTriangleArea: 300.0,
  adaptiveMinAngle: 25.0,
  adaptiveMeshFeatures: [],
  landuseClassToManning: null,
  landuseDefaultManning: null,
  infiltrationEnabled: true,
  infiltrationStrictClasses: true,
  landuseClassToInfiltration: null,
  landuseDefaultInfiltration: null,
  nodeFieldMapping: createDefaultNodeFieldMapping(),
  linkFieldMapping: createDefaultLinkFieldMapping(),
  nodeTypeMapping: {},
  shapeTypeMapping: {}
});

const nodeFieldMapping = ref(createDefaultNodeFieldMapping());
const linkFieldMapping = ref(createDefaultLinkFieldMapping());
const nodeTypeMappingList = ref(createDefaultNodeTypeMapping());
const shapeTypeMappingList = ref(createDefaultShapeTypeMapping());

const mappingConfigFileInput = ref(null);

const tempNodeFieldMapping = ref({});
const tempLinkFieldMapping = ref({});
const tempNodeTypeMappingList = ref([]);
const tempShapeTypeMappingList = ref([]);

const createDefaultInfiltrationParam = () => ({
  maxRate: 0.0,
  minRate: 0.0,
  decay: 0.0,
  dryingTimeDays: 7.0,
  maxInfiltration: 0.0
});

const createLanduseMapping = (classId = null, manning = 0.03, infiltration = {}) => ({
  classId,
  manning,
  ...createDefaultInfiltrationParam(),
  ...infiltration
});

const normalizeInfiltrationParam = (item = {}) => {
  const source = { ...createDefaultInfiltrationParam(), ...item };
  return {
    maxRate: Number(source.maxRate),
    minRate: Number(source.minRate),
    decay: Number(source.decay),
    dryingTimeDays: Number(source.dryingTimeDays),
    maxInfiltration: Number(source.maxInfiltration)
  };
};

const hasInvalidInfiltrationParam = (item = {}) => {
  return ['maxRate', 'minRate', 'decay', 'dryingTimeDays', 'maxInfiltration'].some((key) => {
    const value = item[key];
    return value === null || value === '' || Number.isNaN(Number(value));
  });
};

const landuseMappings = ref([
  createLanduseMapping(1, 0.015),
  createLanduseMapping(2, 0.035),
  createLanduseMapping(3, 0.016),
  createLanduseMapping(4, 0.1)
]);
const landuseDefaultManning = ref(0.02);
const infiltrationEnabled = ref(true);
const infiltrationStrictClasses = ref(true);
const landuseDefaultInfiltration = ref(createDefaultInfiltrationParam());

const tempLanduseMappings = ref([]);
const tempLanduseDefaultManning = ref(0.02);
const tempInfiltrationEnabled = ref(true);
const tempInfiltrationStrictClasses = ref(true);
const tempLanduseDefaultInfiltration = ref(createDefaultInfiltrationParam());
const toMappingObject = (list) => {
  const result = {};
  list.forEach(item => {
    if (item.source && item.target) {
      const trimmedSource = String(item.source).trim();
      if (!trimmedSource) return;
      const targetVal = item.target === 'JUNCTION_CHECK' || item.target === 'JUNCTION_CONN'
        ? 'JUNCTION'
        : item.target;
      result[trimmedSource] = targetVal;
    }
  });
  return result;
};

const regionRules = computed(() => ({
  regionName: [{ required: true, message: t('region.inputRegionName'), trigger: 'blur' }],
  manningsN: [{ required: true, message: t('parameter.inputPrefix') + t('region.manningsN'), trigger: 'blur' }],
  sedimentConcentration: [{ required: true, message: t('parameter.inputPrefix') + t('region.sediment'), trigger: 'blur' }],
  vegetationStemDiameter: [{ required: true, message: t('parameter.inputPrefix') + t('region.stemDiameter'), trigger: 'blur' }],
  vegetationStemSpacing: [{ required: true, message: t('parameter.inputPrefix') + t('region.stemSpacing'), trigger: 'blur' }]
}));

const nodeFileList = ref([]);
const linkFileList = ref([]);
const demFileList = ref([]);
const landuseFileList = ref([]);
const adaptiveMeshFeatures = ref([]);

const nodeUploadRef = ref(null);
const linkUploadRef = ref(null);
const demUploadRef = ref(null);
const landuseUploadRef = ref(null);

const handleNodeFileChange = (file, fileList) => {
  nodeFileList.value = fileList;
};
const handleLinkFileChange = (file, fileList) => {
  linkFileList.value = fileList;
};
const handleDemFileChange = (file, fileList) => {
  demFileList.value = fileList;
};
const handleLanduseFileChange = (file, fileList) => {
  landuseFileList.value = fileList;
};

const createAdaptiveMeshFeature = (featureType = 'building') => ({
  id: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
  featureType,
  meshAction: featureType === 'road' || featureType === 'constraint' ? 'constraint' : 'hole',
  fileList: []
});

const addAdaptiveMeshFeature = () => {
  adaptiveMeshFeatures.value.push(createAdaptiveMeshFeature());
};

const removeAdaptiveMeshFeature = (index) => {
  adaptiveMeshFeatures.value.splice(index, 1);
};

const handleAdaptiveMeshFileChange = (index, fileList) => {
  if (!adaptiveMeshFeatures.value[index]) return;
  adaptiveMeshFeatures.value[index].fileList = fileList;
};

const handleAdaptiveFeatureTypeChange = (item) => {
  if (!item) return;
  item.meshAction = item.featureType === 'road' || item.featureType === 'constraint'
    ? 'constraint'
    : 'hole';
};

const validateShapefileParts = (fileList, label) => {
  const names = fileList.map(file => file.name.toLowerCase());
  const hasShp = names.some(name => name.endsWith('.shp'));
  const hasDbf = names.some(name => name.endsWith('.dbf'));
  const hasShx = names.some(name => name.endsWith('.shx'));
  if (!hasShp || !hasDbf || !hasShx) {
    MxMsg.warning(t('region.shapefilePartsRequired', { label }));
    return false;
  }
  return true;
};

const buildAdaptiveMeshFeaturePayload = () => {
  return adaptiveMeshFeatures.value
    .filter(item => item.fileList && item.fileList.length > 0)
    .map(item => ({
      featureType: item.featureType,
      hole: item.meshAction === 'hole',
      fileNames: item.fileList.map(file => file.name)
    }));
};

const openLanduseMappingDialog = () => {
  tempLanduseMappings.value = JSON.parse(JSON.stringify(landuseMappings.value));
  tempLanduseDefaultManning.value = landuseDefaultManning.value;
  tempInfiltrationEnabled.value = infiltrationEnabled.value;
  tempInfiltrationStrictClasses.value = infiltrationStrictClasses.value;
  tempLanduseDefaultInfiltration.value = JSON.parse(JSON.stringify(landuseDefaultInfiltration.value));
  landuseMappingDialogVisible.value = true;
};

const confirmLanduseMappingConfig = () => {
  const hasInvalidData = landuseMappings.value.some(item => {
    return item.classId === null || item.classId === '' || 
           item.manning === null || item.manning === '';
  });
  
  if (hasInvalidData) {
    MxMsg.warning(t('message.mappingIncomplete'));
    return;
  }

  if (infiltrationEnabled.value) {
    if (hasInvalidInfiltrationParam(landuseDefaultInfiltration.value)) {
      MxMsg.warning(t('message.defaultHortonIncomplete'));
      return;
    }
    const invalidInfiltration = landuseMappings.value.some(item => hasInvalidInfiltrationParam(item));
    if (invalidInfiltration) {
      MxMsg.warning(t('message.hortonIncomplete'));
      return;
    }
  }

  const classIds = landuseMappings.value.map(item => item.classId);
  const uniqueClassIds = new Set(classIds);
  if (classIds.length !== uniqueClassIds.size) {
    MxMsg.warning(t('message.duplicateClassId'));
    return;
  }

  landuseConfigured.value = true;
  landuseMappingDialogVisible.value = false;
  MxMsg.success(t('message.landuseMappingSaved'));
};

const cancelLanduseMappingConfig = () => {
  landuseMappings.value = JSON.parse(JSON.stringify(tempLanduseMappings.value));
  landuseDefaultManning.value = tempLanduseDefaultManning.value;
  infiltrationEnabled.value = tempInfiltrationEnabled.value;
  infiltrationStrictClasses.value = tempInfiltrationStrictClasses.value;
  landuseDefaultInfiltration.value = JSON.parse(JSON.stringify(tempLanduseDefaultInfiltration.value));
  landuseMappingDialogVisible.value = false;
};

const addLanduseMapping = () => {
  landuseMappings.value.push(createLanduseMapping());
};

const removeLanduseMapping = (index) => {
  landuseMappings.value.splice(index, 1);
};

const resetLanduseMappings = () => {
  landuseMappings.value = [
    createLanduseMapping(1, 0.015),
    createLanduseMapping(2, 0.035),
    createLanduseMapping(3, 0.016),
    createLanduseMapping(4, 0.1)
  ];
  landuseDefaultManning.value = 0.02;
  infiltrationEnabled.value = true;
  infiltrationStrictClasses.value = true;
  landuseDefaultInfiltration.value = createDefaultInfiltrationParam();
  landuseConfigured.value = false;
};

const openMappingDialog = () => {
  tempNodeFieldMapping.value = JSON.parse(JSON.stringify(nodeFieldMapping.value));
  tempLinkFieldMapping.value = JSON.parse(JSON.stringify(linkFieldMapping.value));
  tempNodeTypeMappingList.value = JSON.parse(JSON.stringify(nodeTypeMappingList.value));
  tempShapeTypeMappingList.value = JSON.parse(JSON.stringify(shapeTypeMappingList.value));
  mappingDialogVisible.value = true;
};

const addNodeTypeMapping = () => {
  nodeTypeMappingList.value.push({ source: '', target: 'JUNCTION_CHECK' });
};

const removeNodeTypeMapping = (index) => {
  nodeTypeMappingList.value.splice(index, 1);
};

const addShapeTypeMapping = () => {
  shapeTypeMappingList.value.push({ source: '', target: 'CIRCULAR' });
};

const removeShapeTypeMapping = (index) => {
  shapeTypeMappingList.value.splice(index, 1);
};

const resetMappingToDefault = () => {
  nodeFieldMapping.value = createDefaultNodeFieldMapping();
  linkFieldMapping.value = createDefaultLinkFieldMapping();
  nodeTypeMappingList.value = createDefaultNodeTypeMapping();
  shapeTypeMappingList.value = createDefaultShapeTypeMapping();
};

const cancelMappingConfig = () => {
  nodeFieldMapping.value = JSON.parse(JSON.stringify(tempNodeFieldMapping.value));
  linkFieldMapping.value = JSON.parse(JSON.stringify(tempLinkFieldMapping.value));
  nodeTypeMappingList.value = JSON.parse(JSON.stringify(tempNodeTypeMappingList.value));
  shapeTypeMappingList.value = JSON.parse(JSON.stringify(tempShapeTypeMappingList.value));
  mappingDialogVisible.value = false;
};

const confirmMappingConfig = () => {
  mappingDialogVisible.value = false;
  MxMsg.success(t('message.fieldMappingSaved'));
};

const generateMappingConfigContent = () => {
  const lines = [];
  lines.push('# UrbanFlood Shapefile Mapping Config v1');
  lines.push(`# ${t('region.mappingConfigEditable')}`);
  lines.push(`# ${t('region.mappingConfigEditHint')}`);
  lines.push('');
  lines.push(`# ${t('region.nodeFieldMapping')}`);
  lines.push('[node_fields]');
  Object.keys(nodeFieldLabels.value).forEach((k) => {
    const label = nodeFieldLabels.value[k];
    lines.push(`${label}=${nodeFieldMapping.value[k] || ''}`);
  });
  lines.push('');
  lines.push(`# ${t('region.linkFieldMapping')}`);
  lines.push('[link_fields]');
  Object.keys(linkFieldLabels.value).forEach((k) => {
    const label = linkFieldLabels.value[k];
    lines.push(`${label}=${linkFieldMapping.value[k] || ''}`);
  });
  lines.push('');
  lines.push(`# ${t('region.nodeTypeMapping')}`);
  lines.push(`# ${t('region.optionalTypes', { values: Object.values(nodeTypeDisplay.value).join(locale.value === 'zh-CN' ? '、' : ', ') })}`);
  lines.push(`# ${t('region.multipleMappings')}`);
  lines.push('');
  lines.push('[node_type_mapping]');

  if (nodeTypeMappingList.value.length === 0) {
    lines.push(`# ${t('region.mappingExample', { value: `${t('region.inspectionWell')}=jcj` })}`);
  } else {
    nodeTypeMappingList.value.forEach(item => {
      if (item.source || item.target) {
        const targetLabel = nodeTypeDisplay.value[item.target] || item.target || '';
        lines.push(`${targetLabel}=${item.source || ''}`);
      }
    });
  }
  lines.push('');
  lines.push(`# ${t('region.shapeMapping')}`);
  lines.push(`# ${t('region.optionalShapes', { values: Object.values(shapeTypeDisplay.value).join(locale.value === 'zh-CN' ? '、' : ', ') })}`);
  lines.push(`# ${t('region.multipleMappings')}`);
  lines.push('');
  lines.push('[shape_type_mapping]');


  if (shapeTypeMappingList.value.length === 0) {
    lines.push(`# ${t('region.mappingExample', { value: `${t('region.circular')}=yx` })}`);
  } else {
    shapeTypeMappingList.value.forEach(item => {
      if (item.source || item.target) {
        const targetLabel = shapeTypeDisplay.value[item.target] || item.target || '';
        lines.push(`${targetLabel}=${item.source || ''}`);
      }
    });
  }

  return lines.join('\n');
};

const downloadMappingConfig = () => {
  const content = generateMappingConfigContent();
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const name = regionForm.value.regionName || 'mapping';
  a.href = url;
  a.download = `${name}-mapping-config.txt`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  MxMsg.success(t('message.mappingDownloaded'));
};

const triggerUploadMappingConfig = () => {
  if (mappingConfigFileInput.value) {
    mappingConfigFileInput.value.value = '';
    mappingConfigFileInput.value.click();
  }
};

const parseMappingConfig = (text) => {
  const result = {
    nodeFields: {},
    linkFields: {},
    nodeTypes: [],
    shapeTypes: []
  };
  const lines = text.split(/\r?\n/);
  let section = '';
  lines.forEach(raw => {
    const line = raw.trim();
    if (!line || line.startsWith('#')) return;
    if (line.startsWith('[') && line.endsWith(']')) {
      section = line.slice(1, -1).toLowerCase();
      return;
    }
    const eqIdx = line.indexOf('=');
    if (eqIdx <= 0) return;
    const key = line.slice(0, eqIdx).trim();
    const value = line.slice(eqIdx + 1).trim();
    switch (section) {
      case 'node_fields':
        result.nodeFields[nodeLabelToKey[key] || key] = value;
        break;
      case 'link_fields':
        result.linkFields[linkLabelToKey[key] || key] = value;
        break;
      case 'node_type_mapping':
        if (value.includes('->')) {
          const parts = value.split('->');
          const targetLabel = (parts[1] || '').trim();
          const targetCode = nodeTypeCodeFromLabel[targetLabel] || targetLabel;
          result.nodeTypes.push({ source: (parts[0] || '').trim(), target: targetCode });
        } else {
          result.nodeTypes.push({ source: value, target: nodeTypeCodeFromLabel[key] || key });
        }
        break;
      case 'shape_type_mapping':
        if (value.includes('->')) {
          const parts = value.split('->');
          const targetLabel = (parts[1] || '').trim();
          const targetCode = shapeTypeCodeFromLabel[targetLabel] || targetLabel;
          result.shapeTypes.push({ source: (parts[0] || '').trim(), target: targetCode });
        } else {
          result.shapeTypes.push({ source: value, target: shapeTypeCodeFromLabel[key] || key });
        }
        break;
      default:
        break;
    }
  });
  return result;
};

const applyMappingConfig = (config) => {
  if (config.nodeFields && Object.keys(config.nodeFields).length > 0) {
    nodeFieldMapping.value = { ...nodeFieldMapping.value, ...config.nodeFields };
  }
  if (config.linkFields && Object.keys(config.linkFields).length > 0) {
    linkFieldMapping.value = { ...linkFieldMapping.value, ...config.linkFields };
  }
  if (config.nodeTypes && config.nodeTypes.length > 0) {
    nodeTypeMappingList.value = config.nodeTypes.map(item => ({ ...item }));
  }
  if (config.shapeTypes && config.shapeTypes.length > 0) {
    shapeTypeMappingList.value = config.shapeTypes.map(item => ({ ...item }));
  }
};

const handleMappingConfigUpload = (event) => {
  const file = event.target.files && event.target.files[0];
  if (!file) return;
  if (!file.name.endsWith('.txt')) {
    MxMsg.warning(t('message.mappingTxtOnly'));
    return;
  }
  const reader = new FileReader();
  reader.onload = (e) => {
    try {
      const text = e.target.result;
      const config = parseMappingConfig(text);
      applyMappingConfig(config);
      MxMsg.success(t('message.mappingLoaded'));
    } catch (err) {
      console.error('解析映射配置失败:', err);
      MxMsg.error(t('message.mappingParseFailed'));
    }
  };
  reader.readAsText(file, 'utf-8');
};

const showAddDialog = () => {
  resetForm();
  addDialogVisible.value = true;
};

const resetForm = () => {
  regionForm.value = {
    regionName: '',
    description: '',
    manningsN: 0.03,
    sedimentConcentration: 0.0,
    vegetationStemDiameter: 0.01,
    vegetationStemSpacing: 1.0,
    tinGridType: 'adaptive',
    regularGridSize: 10.0,
    adaptiveMaxTriangleArea: 300.0,
    adaptiveMinAngle: 25.0,
    adaptiveMeshFeatures: [],
    landuseClassToManning: null,
    landuseDefaultManning: null,
    infiltrationEnabled: true,
    infiltrationStrictClasses: true,
    landuseClassToInfiltration: null,
    landuseDefaultInfiltration: null,
    nodeFieldMapping: createDefaultNodeFieldMapping(),
    linkFieldMapping: createDefaultLinkFieldMapping(),
    nodeTypeMapping: toMappingObject(createDefaultNodeTypeMapping()),
    shapeTypeMapping: toMappingObject(createDefaultShapeTypeMapping())
  };
  nodeFileList.value = [];
  linkFileList.value = [];
  demFileList.value = [];
  landuseFileList.value = [];
  adaptiveMeshFeatures.value = [createAdaptiveMeshFeature()];
  resetLanduseMappings();
  resetMappingToDefault();
};

const submitRegion = async () => {
  try {
    await regionFormRef.value.validate();

    if (nodeFileList.value.length === 0) {
      MxMsg.warning(t('message.uploadNodeShp'));
      return;
    }
    if (linkFileList.value.length === 0) {
      MxMsg.warning(t('message.uploadLinkShp'));
      return;
    }
    if (demFileList.value.length === 0) {
      MxMsg.warning(t('message.uploadDem'));
      return;
    }
    
    const hasAscFile = demFileList.value.some(f => f.name.endsWith('.asc'));
    const hasDemPrjFile = demFileList.value.some(f => f.name.endsWith('.prj'));
    if (!hasAscFile || !hasDemPrjFile) {
      MxMsg.warning(t('message.demParts'));
      return;
    }

    if (regionForm.value.tinGridType === 'adaptive') {
      if (!regionForm.value.adaptiveMaxTriangleArea || regionForm.value.adaptiveMaxTriangleArea <= 0) {
        MxMsg.warning(t('message.invalidMaxArea'));
        return;
      }
      if (!regionForm.value.adaptiveMinAngle || regionForm.value.adaptiveMinAngle <= 0) {
        MxMsg.warning(t('message.invalidMinAngle'));
        return;
      }
      for (let i = 0; i < adaptiveMeshFeatures.value.length; i += 1) {
        const item = adaptiveMeshFeatures.value[i];
        if (!item.fileList || item.fileList.length === 0) {
          continue;
        }
        if (!validateShapefileParts(item.fileList, t('region.constraintGroup', { index: i + 1 }))) {
          return;
        }
      }
    }
    
    
    const hasLanduseAsc = landuseFileList.value.some(f => f.name.endsWith('.asc'));
    const hasLandusePrj = landuseFileList.value.some(f => f.name.endsWith('.prj'));
    if (!hasLanduseAsc || !hasLandusePrj) {
      MxMsg.warning(t('message.landuseParts'));
      return;
    }

    if (infiltrationEnabled.value) {
      if (hasInvalidInfiltrationParam(landuseDefaultInfiltration.value)) {
        MxMsg.warning(t('message.defaultHortonIncomplete'));
        return;
      }
      const invalidInfiltration = landuseMappings.value.some(item => hasInvalidInfiltrationParam(item));
      if (invalidInfiltration) {
        MxMsg.warning(t('message.hortonIncomplete'));
        return;
      }
    }
    

    submitting.value = true;

    const formData = new FormData();

    nodeFileList.value.forEach(file => {
      formData.append('nodeShpFiles', file.raw);
    });

    linkFileList.value.forEach(file => {
      formData.append('linkShpFiles', file.raw);
    });

    demFileList.value.forEach(file => {
      if (file.name.endsWith('.asc')) {
        formData.append('ascFile', file.raw);
      } else if (file.name.endsWith('.prj')) {
        formData.append('prjFile', file.raw);
      }
    });

    if (regionForm.value.tinGridType === 'adaptive') {
      adaptiveMeshFeatures.value.forEach(item => {
        if (!item.fileList || item.fileList.length === 0) return;
        item.fileList.forEach(file => {
          formData.append('adaptiveMeshShpFiles', file.raw);
        });
      });
      regionForm.value.adaptiveMeshFeatures = buildAdaptiveMeshFeaturePayload();
    } else {
      regionForm.value.adaptiveMeshFeatures = [];
    }

    if (landuseFileList.value.length > 0) {
      landuseFileList.value.forEach(file => {
        if (file.name.endsWith('.asc')) {
          formData.append('landuseFile', file.raw);
        } else if (file.name.endsWith('.prj')) {
          formData.append('landusePrjFile', file.raw);
        }
      });
      
      const classToManningMap = {};
      const classToInfiltrationMap = {};
      landuseMappings.value.forEach(item => {
        if (item.classId !== null && item.classId !== '') {
          classToManningMap[item.classId] = item.manning;
          classToInfiltrationMap[item.classId] = normalizeInfiltrationParam(item);
        }
      });
      
      regionForm.value.infiltrationEnabled = infiltrationEnabled.value;
      regionForm.value.infiltrationStrictClasses = infiltrationStrictClasses.value;
      regionForm.value.landuseClassToManning = classToManningMap;
      regionForm.value.landuseDefaultManning = landuseDefaultManning.value;
      regionForm.value.landuseClassToInfiltration = classToInfiltrationMap;
      regionForm.value.landuseDefaultInfiltration = normalizeInfiltrationParam(landuseDefaultInfiltration.value);
    } else {
      regionForm.value.infiltrationEnabled = infiltrationEnabled.value;
      regionForm.value.infiltrationStrictClasses = infiltrationStrictClasses.value;
      regionForm.value.landuseClassToManning = null;
      regionForm.value.landuseDefaultManning = null;
      regionForm.value.landuseClassToInfiltration = null;
      regionForm.value.landuseDefaultInfiltration = null;
    }

    regionForm.value.nodeFieldMapping = JSON.parse(JSON.stringify(nodeFieldMapping.value));
    regionForm.value.linkFieldMapping = JSON.parse(JSON.stringify(linkFieldMapping.value));
    regionForm.value.nodeTypeMapping = toMappingObject(nodeTypeMappingList.value);
    regionForm.value.shapeTypeMapping = toMappingObject(shapeTypeMappingList.value);

    formData.append('bo', new Blob([JSON.stringify(regionForm.value)], { type: 'application/json' }));
    if (props.publicMode) {
      formData.append('sessionId', ensureSessionId());
    }

    await (props.publicMode ? API.publicFloodRegionInitialize : API.floodRegionInitialize)({ data: formData });

    MxMsg.success(t('message.regionSubmitted'));
    addDialogVisible.value = false;
    refreshRegionList();

  } catch (error) {
    console.error('提交失败:', error);
  } finally {
    submitting.value = false;
  }
};

const refreshRegionList = async () => {
  loading.value = true;
  try {
    const res = await (props.publicMode ? API.publicFloodRegionList : API.floodRegionList)(
      props.publicMode ? { data: { sessionId: ensureSessionId() } } : undefined
    );
    regionList.value = res.data || [];
  } catch (error) {
    console.error('获取区域列表失败:', error);
  } finally {
    loading.value = false;
  }
};

const viewDetail = (row) => {
  const statusLabels = [t('region.uninitialized'), t('region.initializing'), t('region.completed'), t('region.initFailed')];
  const errorMessage = locale.value === 'zh-CN' || !/[\u3400-\u9fff]/.test(row.errorMessage || '')
    ? row.errorMessage
    : t('common.unknown');
  MxMsgBox.alert(
    `<div>
      <p><strong>${t('region.regionName')}:</strong> ${row.regionName}</p>
      <p><strong>${t('common.description')}:</strong> ${row.description || t('common.none')}</p>
      <p><strong>${t('region.initStatus')}:</strong> ${statusLabels[row.initStatus] || t('common.unknown')}</p>
      ${props.publicMode ? '' : `<p><strong>${t('region.dataPath')}:</strong> ${row.dataPath || t('common.none')}</p>`}
      ${props.publicMode ? '' : `<p><strong>${t('region.tinFile')}:</strong> ${row.tinFilePath || t('common.none')}</p>`}
      ${row.errorMessage ? `<p><strong>${t('region.errorInfo')}:</strong> ${errorMessage}</p>` : ''}
    </div>`,
    t('region.detailTitle'),
    { dangerouslyUseHTMLString: true }
  );
};

const deleteRegion = (row) => {
  MxMsgBox.confirm(t('region.deleteConfirm', { name: row.regionName }), t('common.tip'), {
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel'),
    type: 'warning'
  }).then(async () => {
    try {
      await (props.publicMode ? API.publicFloodRegionDelete : API.floodRegionDelete)({
        data: {
          regionId: row.id,
          ...(props.publicMode ? { sessionId: ensureSessionId() } : {}),
        },
      });
      MxMsg.success(t('message.deleteSuccess'));
      refreshRegionList();
    } catch (error) {
      console.error('删除失败:', error);
    }
  }).catch(() => { });
};

const handleClose = () => {
  dialogVisible.value = false;
};

const handleAddDialogClose = () => {
};

defineExpose({
  dialogVisible,
  refreshRegionList
});

onMounted(() => {
  // refreshRegionList();
});
</script>

<style scoped lang="scss">
.region-manage-container {
  .operation-bar {
    margin-bottom: 16px;
  }
}

:deep(.el-form-item) {
  margin-bottom: 18px;
}

:deep(.el-divider--horizontal) {
  margin: 20px 0;
}

:deep(.el-upload__tip) {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

:deep(.el-scrollbar__wrap) {
  overflow-x: hidden;
}

.adaptive-shp-panel {
  width: 100%;
}

.adaptive-shp-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: #606266;
  font-size: 13px;
}

.adaptive-shp-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.adaptive-shp-item {
  padding: 12px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
}

.field-caption {
  margin-bottom: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 18px;
}

.landuse-infiltration-row {
  margin-top: 12px;
}
</style>
