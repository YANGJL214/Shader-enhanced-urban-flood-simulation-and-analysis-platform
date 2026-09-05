package cn.com.medoxcq.module.warning.module.simulation.service.impl;

import cn.com.medoxcq.common.exception.BusinessException;
import cn.com.medoxcq.common.exception.enums.ErrorCodeEnum;
import cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion;
import cn.com.medoxcq.module.warning.module.simulation.mapper.FloodRegionMapper;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.FloodRegionSaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.FloodRegionVO;
import cn.com.medoxcq.module.warning.module.simulation.service.FloodRegionService;
import cn.com.medoxcq.module.warning.config.SimulationConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 *
 * @author system
 * @since 2025/12/11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FloodRegionServiceImpl implements FloodRegionService {

    private final FloodRegionMapper floodRegionMapper;
    private final UrbanFloodServiceImpl urbanFloodService;
    private final SimulationConfiguration simulationProperties;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private String getRegionDataRoot() {
        String regionRoot = simulationProperties.getUrbanFloodRegionRoot();
        if (regionRoot == null || regionRoot.trim().isEmpty()) {
            throw new IllegalStateException("simulation.urban-flood-region-root is not configured");
        }
        return Paths.get(regionRoot).toAbsolutePath().normalize().toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String initializeRegion(FloodRegionSaveBO bo,
                                   MultipartFile[] nodeShpFiles,
                                   MultipartFile[] linkShpFiles,
                                   MultipartFile ascFile,
                                   MultipartFile prjFile,
                                   MultipartFile landuseFile,
                                   MultipartFile landusePrjFile,
                                   MultipartFile[] adaptiveMeshShpFiles) throws Exception {
        return initializeRegionInternal(bo, nodeShpFiles, linkShpFiles, ascFile, prjFile,
                landuseFile, landusePrjFile, adaptiveMeshShpFiles, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String initializePublicRegion(FloodRegionSaveBO bo,
                                         MultipartFile[] nodeShpFiles,
                                         MultipartFile[] linkShpFiles,
                                         MultipartFile ascFile,
                                         MultipartFile prjFile,
                                         MultipartFile landuseFile,
                                         MultipartFile landusePrjFile,
                                         MultipartFile[] adaptiveMeshShpFiles,
                                         String ownerKey) throws Exception {
        return initializeRegionInternal(bo, nodeShpFiles, linkShpFiles, ascFile, prjFile,
                landuseFile, landusePrjFile, adaptiveMeshShpFiles, ownerKey);
    }

    private String initializeRegionInternal(FloodRegionSaveBO bo,
                                            MultipartFile[] nodeShpFiles,
                                            MultipartFile[] linkShpFiles,
                                            MultipartFile ascFile,
                                            MultipartFile prjFile,
                                            MultipartFile landuseFile,
                                            MultipartFile landusePrjFile,
                                            MultipartFile[] adaptiveMeshShpFiles,
                                            String ownerKey) throws Exception {
        
        log.info("开始初始化区域: {}", bo.getRegionName());
        
        LambdaQueryWrapper<FloodRegion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FloodRegion::getRegionName, bo.getRegionName());
        if (ownerKey != null) {
            queryWrapper.eq(FloodRegion::getCreateBy, ownerKey);
        }
        if (bo.getId() != null) {
            queryWrapper.ne(FloodRegion::getId, bo.getId());
            FloodRegion existing = floodRegionMapper.selectById(bo.getId());
            if (ownerKey != null && (existing == null || !Objects.equals(ownerKey, existing.getCreateBy()))) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "无权修改该区域");
            }
        }
        if (floodRegionMapper.selectCount(queryWrapper) > 0) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域名称已存在");
        }
        
        FloodRegion region = new FloodRegion();
        if (bo.getId() == null) {
            region.setId(UUID.randomUUID().toString());
        } else {
            region.setId(bo.getId());
        }
        region.setRegionName(bo.getRegionName());
        region.setDescription(bo.getDescription());
        region.setInitStatus(1);
        if (ownerKey != null) {
            region.setCreateBy(ownerKey);
            region.setUpdateBy(ownerKey);
        }
        
        Map<String, Object> configParams = new HashMap<>();
        configParams.put("manningsN", bo.getManningsN());
        configParams.put("sedimentConcentration", bo.getSedimentConcentration());
        configParams.put("vegetationStemDiameter", bo.getVegetationStemDiameter());
        configParams.put("vegetationStemSpacing", bo.getVegetationStemSpacing());
        configParams.put("landuseClassToManning", bo.getLanduseClassToManning());
        configParams.put("landuseDefaultManning", bo.getLanduseDefaultManning());
        configParams.put("infiltrationEnabled", bo.getInfiltrationEnabled());
        configParams.put("infiltrationStrictClasses", bo.getInfiltrationStrictClasses());
        configParams.put("landuseClassToInfiltration", bo.getLanduseClassToInfiltration());
        configParams.put("landuseDefaultInfiltration", bo.getLanduseDefaultInfiltration());
        configParams.put("tinGridType", bo.getTinGridType());
        configParams.put("regularGridSize", bo.getRegularGridSize());
        configParams.put("adaptiveMaxTriangleArea", bo.getAdaptiveMaxTriangleArea());
        configParams.put("adaptiveMinAngle", bo.getAdaptiveMinAngle());
        configParams.put("adaptiveMeshFeatures", bo.getAdaptiveMeshFeatures());
        
        if (bo.getNodeFieldMapping() != null) {
            configParams.put("nodeFieldMapping", bo.getNodeFieldMapping());
        }
        if (bo.getLinkFieldMapping() != null) {
            configParams.put("linkFieldMapping", bo.getLinkFieldMapping());
        }
        if (bo.getNodeTypeMapping() != null) {
            configParams.put("nodeTypeMapping", bo.getNodeTypeMapping());
        }
        if (bo.getShapeTypeMapping() != null) {
            configParams.put("shapeTypeMapping", bo.getShapeTypeMapping());
        }
        
        region.setConfigParams(JSON_MAPPER.writeValueAsString(configParams));
        
        if (bo.getId() == null) {
            floodRegionMapper.insert(region);
        } else {
            floodRegionMapper.updateById(region);
        }
        
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeRegionName = bo.getRegionName()
                    .replaceAll("[^\\p{L}\\p{N}._-]", "_");
            if (safeRegionName.length() > 40) {
                safeRegionName = safeRegionName.substring(0, 40);
            }
            String regionDirName = safeRegionName + "_" + region.getId() + "_" + timestamp;
            Path regionRoot = Paths.get(getRegionDataRoot()).toAbsolutePath().normalize();
            Path regionDataPath = regionRoot.resolve(regionDirName).normalize();
            if (!regionDataPath.startsWith(regionRoot)) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域名称包含非法路径");
            }
            Files.createDirectories(regionDataPath);
            
            String demPath = saveFile(ascFile, regionDataPath, "terrain.asc");
            String prjPath = saveFile(prjFile, regionDataPath, "terrain.prj");

            List<UrbanFloodServiceImpl.AdaptiveTinFeature> adaptiveTinFeatures = Collections.emptyList();
            if ("adaptive".equalsIgnoreCase(bo.getTinGridType())) {
                adaptiveTinFeatures = saveAdaptiveMeshShapefiles(
                        adaptiveMeshShpFiles,
                        bo.getAdaptiveMeshFeatures(),
                        regionDataPath);
            }
            
            log.info("开始生成TIN网格，类型: {}", bo.getTinGridType());
            UrbanFloodServiceImpl.TinCsvPaths tinCsvPaths;
            if ("regular".equalsIgnoreCase(bo.getTinGridType())) {
                Double gridSize = bo.getRegularGridSize() != null ? bo.getRegularGridSize() : 10.0;
                tinCsvPaths = urbanFloodService.generateRegularGridTinFromDemFile(demPath, gridSize);
                log.info("规则网格TIN生成完成，网格大小: {} 米", gridSize);
            } else {
                double maxArea = bo.getAdaptiveMaxTriangleArea() != null ? bo.getAdaptiveMaxTriangleArea() : 300.0;
                double minAngle = bo.getAdaptiveMinAngle() != null ? bo.getAdaptiveMinAngle() : 25.0;
                tinCsvPaths = urbanFloodService.generateConstrainedAdaptiveTinFromDemFile(
                        demPath,
                        adaptiveTinFeatures,
                        maxArea,
                        minAngle);
                log.info("自适应约束TIN网格生成完成，最大面积: {}，最小角度: {}，约束地物数: {}",
                        maxArea, minAngle, adaptiveTinFeatures.size());
            }
            String tinBaseCsvPath = tinCsvPaths.getBaseCsvPath();
            log.info("TIN网格CSV已生成: poi={}, tri={}", tinCsvPaths.getPoiCsvPath(), tinCsvPaths.getTriCsvPath());
            
            String nodeShpPath = saveShapefiles(nodeShpFiles, regionDataPath, "node_shp");
            String linkShpPath = saveShapefiles(linkShpFiles, regionDataPath, "link_shp");
            String landusePath = null;
            if (landuseFile != null && !landuseFile.isEmpty()) {
                landusePath = saveFile(landuseFile, regionDataPath, normalizeLanduseFileName(landuseFile));
                if (landusePrjFile != null && !landusePrjFile.isEmpty()) {
                    String landusePrjFileName = landusePath.replace(".asc", ".prj");
                    saveFile(landusePrjFile, regionDataPath, Paths.get(landusePrjFileName).getFileName().toString());
                    log.info("土地利用投影文件已保存: {}", landusePrjFileName);
                }
            }
            
            region.setDataPath(regionDataPath.toString());
            region.setTinFilePath(tinBaseCsvPath);
            region.setDemFilePath(demPath);
            region.setNodeShpPath(nodeShpPath);
            region.setLinkShpPath(linkShpPath);
            region.setLanduseFilePath(landusePath);
            region.setInitStatus(2);
            region.setErrorMessage(null);
            floodRegionMapper.updateById(region);
            
            log.info("区域初始化完成: {} (ID: {})", bo.getRegionName(), region.getId());
            return region.getId();
            
        } catch (Exception e) {
            log.error("区域初始化失败: {}", e.getMessage(), e);
            
            region.setInitStatus(3);
            region.setErrorMessage(e.getMessage());
            floodRegionMapper.updateById(region);
            
            throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), "区域初始化失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateRegion(FloodRegionSaveBO bo) throws Exception {
        if (bo.getId() == null) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域ID不能为空");
        }
        
        FloodRegion region = floodRegionMapper.selectById(bo.getId());
        if (region == null) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域不存在");
        }
        
        region.setRegionName(bo.getRegionName());
        region.setDescription(bo.getDescription());
        
        Map<String, Object> configParams = new HashMap<>();
        configParams.put("manningsN", bo.getManningsN());
        configParams.put("sedimentConcentration", bo.getSedimentConcentration());
        configParams.put("vegetationStemDiameter", bo.getVegetationStemDiameter());
        configParams.put("vegetationStemSpacing", bo.getVegetationStemSpacing());
        configParams.put("landuseClassToManning", bo.getLanduseClassToManning());
        configParams.put("landuseDefaultManning", bo.getLanduseDefaultManning());
        configParams.put("infiltrationEnabled", bo.getInfiltrationEnabled());
        configParams.put("infiltrationStrictClasses", bo.getInfiltrationStrictClasses());
        configParams.put("landuseClassToInfiltration", bo.getLanduseClassToInfiltration());
        configParams.put("landuseDefaultInfiltration", bo.getLanduseDefaultInfiltration());
        configParams.put("tinGridType", bo.getTinGridType());
        configParams.put("regularGridSize", bo.getRegularGridSize());
        configParams.put("adaptiveMaxTriangleArea", bo.getAdaptiveMaxTriangleArea());
        configParams.put("adaptiveMinAngle", bo.getAdaptiveMinAngle());
        configParams.put("adaptiveMeshFeatures", bo.getAdaptiveMeshFeatures());
        region.setConfigParams(JSON_MAPPER.writeValueAsString(configParams));
        
        return floodRegionMapper.updateById(region) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRegion(String regionId) throws Exception {
        if (regionId == null || regionId.trim().isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域ID不能为空");
        }
        regionId = regionId.trim();
        FloodRegion region = floodRegionMapper.selectById(regionId);
        if (region == null) {
            region = floodRegionMapper.selectOne(new LambdaQueryWrapper<FloodRegion>()
                    .eq(FloodRegion::getRegionName, regionId)
                    .last("limit 1"));
        }
        if (region == null) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域不存在");
        }
        
        try {
            if (region.getDataPath() != null) {
                Path dataPath = Paths.get(region.getDataPath());
                if (Files.exists(dataPath)) {
                    log.info("删除区域数据目录: {}", dataPath);
                    // Files.walk(dataPath).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (Exception e) {
            log.warn("删除区域数据目录失败: {}", e.getMessage());
        }
        
        return floodRegionMapper.deleteById(regionId) > 0;
    }

    @Override
    public FloodRegionVO getRegionDetail(String regionId) throws Exception {
        FloodRegion region = floodRegionMapper.selectById(regionId);
        if (region == null) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域不存在");
        }
        
        return convertToVO(region);
    }

    @Override
    public List<FloodRegionVO> listRegions() throws Exception {
        List<FloodRegion> regions = floodRegionMapper.selectList(new LambdaQueryWrapper<>());
        return regions.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FloodRegionVO> listInitializedRegions() throws Exception {
        LambdaQueryWrapper<FloodRegion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FloodRegion::getInitStatus, 2);
        
        List<FloodRegion> regions = floodRegionMapper.selectList(queryWrapper);
        return regions.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<FloodRegionVO> listRegionsByOwner(String ownerKey, boolean initializedOnly) throws Exception {
        LambdaQueryWrapper<FloodRegion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FloodRegion::getCreateBy, ownerKey);
        if (initializedOnly) {
            queryWrapper.eq(FloodRegion::getInitStatus, 2);
        }
        return floodRegionMapper.selectList(queryWrapper).stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public FloodRegionVO getRegionDetailByOwner(String regionId, String ownerKey) throws Exception {
        FloodRegion region = floodRegionMapper.selectById(regionId);
        if (region == null || !Objects.equals(ownerKey, region.getCreateBy())) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域不存在或无权访问");
        }
        return convertToVO(region);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteRegionByOwner(String regionId, String ownerKey) throws Exception {
        getRegionDetailByOwner(regionId, ownerKey);
        return deleteRegion(regionId);
    }
    private FloodRegionVO convertToVO(FloodRegion region) {
        FloodRegionVO vo = new FloodRegionVO();
        BeanUtils.copyProperties(region, vo);
        
        try {
            if (region.getConfigParams() != null) {
                Map<String, Object> configParams = JSON_MAPPER.readValue(
                    region.getConfigParams(), 
                    new TypeReference<Map<String, Object>>() {}
                );
                vo.setConfigParams(configParams);
            }
        } catch (Exception e) {
            log.warn("解析配置参数失败: {}", e.getMessage());
        }
        
        return vo;
    }
    private String saveShapefiles(MultipartFile[] files, Path dir, String prefix) throws Exception {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Shapefile文件不能为空");
        }
        
        String shpPath = null;
        for (MultipartFile file : files) {
            String originalName = file.getOriginalFilename();
            if (originalName == null) continue;
            
            String extension = originalName.substring(originalName.lastIndexOf("."));
            String fileName = prefix + extension;
            Path targetPath = dir.resolve(fileName);
            file.transferTo(targetPath.toFile());
            
            if (extension.equalsIgnoreCase(".shp")) {
                shpPath = targetPath.toString();
            }
        }
        
        if (shpPath == null) {
            throw new IllegalArgumentException("未找到.shp文件");
        }
        
        return shpPath;
    }
    private List<UrbanFloodServiceImpl.AdaptiveTinFeature> saveAdaptiveMeshShapefiles(
            MultipartFile[] files,
            List<FloodRegionSaveBO.AdaptiveMeshFeatureBO> featureConfigs,
            Path regionDataPath) throws Exception {

        if (featureConfigs == null || featureConfigs.isEmpty()) {
            return Collections.emptyList();
        }
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("自适应三角网地物已配置，但未上传约束Shapefile文件");
        }

        Path meshDir = regionDataPath.resolve("adaptive_mesh_shp");
        Files.createDirectories(meshDir);

        Map<String, Path> savedByOriginalName = new HashMap<>();
        Map<String, Path> savedByBaseAndExt = new HashMap<>();

        for (MultipartFile file : files) {
            String originalName = safeOriginalFileName(file.getOriginalFilename());
            if (originalName == null || originalName.isEmpty()) {
                continue;
            }

            Path targetPath = uniqueTargetPath(meshDir, originalName);
            file.transferTo(targetPath.toFile());
            savedByOriginalName.put(originalName, targetPath);

            String baseAndExt = baseAndExtensionKey(originalName);
            if (baseAndExt != null) {
                savedByBaseAndExt.putIfAbsent(baseAndExt, targetPath);
            }
        }

        List<UrbanFloodServiceImpl.AdaptiveTinFeature> result = new ArrayList<>();
        for (FloodRegionSaveBO.AdaptiveMeshFeatureBO config : featureConfigs) {
            if (config == null || config.getFileNames() == null || config.getFileNames().isEmpty()) {
                continue;
            }

            String shpName = config.getFileNames().stream()
                    .map(this::safeOriginalFileName)
                    .filter(name -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".shp"))
                    .findFirst()
                    .orElse(null);

            if (shpName == null) {
                throw new IllegalArgumentException("自适应三角网地物缺少.shp文件");
            }

            Path shpPath = savedByOriginalName.get(shpName);
            if (shpPath == null) {
                shpPath = savedByBaseAndExt.get(baseAndExtensionKey(shpName));
            }
            if (shpPath == null || !Files.exists(shpPath)) {
                throw new IllegalArgumentException("未找到已保存的自适应三角网Shapefile: " + shpName);
            }

            boolean hole = Boolean.TRUE.equals(config.getHole());
            String featureType = config.getFeatureType();
            if (featureType == null || featureType.trim().isEmpty()) {
                featureType = hole ? "building" : "road";
            }

            result.add(new UrbanFloodServiceImpl.AdaptiveTinFeature(
                    shpPath.toString(),
                    hole,
                    featureType));
        }

        return result;
    }

    private Path uniqueTargetPath(Path dir, String originalName) {
        Path target = dir.resolve(originalName);
        if (!Files.exists(target)) {
            return target;
        }

        int dotIndex = originalName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? originalName.substring(0, dotIndex) : originalName;
        String extension = dotIndex > 0 ? originalName.substring(dotIndex) : "";

        int index = 1;
        while (true) {
            Path candidate = dir.resolve(baseName + "_" + index + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            index++;
        }
    }

    private String safeOriginalFileName(String originalName) {
        if (originalName == null) {
            return null;
        }
        String normalized = originalName.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(slashIndex + 1);
        }
        return normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String baseAndExtensionKey(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = fileName.substring(0, dotIndex).toLowerCase(Locale.ROOT);
        String extension = fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
        return baseName + extension;
    }
    private String saveFile(MultipartFile file, Path dir, String fileName) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空: " + fileName);
        }
        
        Path targetPath = dir.resolve(fileName);
        file.transferTo(targetPath.toFile());
        return targetPath.toString();
    }
    private String normalizeLanduseFileName(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.contains(".")) {
            return "landuse.asc";
        }
        String ext = originalName.substring(originalName.lastIndexOf(".")).toLowerCase();
        if (!ext.equals(".tif") && !ext.equals(".tiff") && !ext.equals(".asc")) {
            ext = ".asc";
        }
        return "landuse" + ext;
    }
}
