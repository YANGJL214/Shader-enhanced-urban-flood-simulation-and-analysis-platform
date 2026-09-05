package cn.com.medoxcq.module.warning.module.simulation.controller.publicapi;

import cn.com.medoxcq.common.pojo.CommonResult;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.UrbanFloodSimulationBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.FloodRegionSaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.PublicFloodRegionVO;
import cn.com.medoxcq.module.warning.module.simulation.service.FloodRegionService;
import cn.com.medoxcq.module.warning.module.simulation.service.UrbanFloodService;
import cn.com.medoxcq.module.warning.module.simulation.service.PublicSimulationAccessService;
import cn.com.medoxcq.common.util.BeanUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
@Tag(name = "公共城市洪涝区域查询")
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/flood_region")
public class PublicFloodRegionController {

  private final FloodRegionService floodRegionService;
  private final UrbanFloodService urbanFloodService;
  private final PublicSimulationAccessService publicAccessService;

  @Operation(summary = "公共获取初始化完成的区域列表")
  @GetMapping("/list/initialized")
  public CommonResult<List<PublicFloodRegionVO>> listInitializedRegions(@RequestParam String sessionId) throws Exception {
    String ownerKey = publicAccessService.publicOwnerKey(sessionId);
    List<PublicFloodRegionVO> regions = floodRegionService.listRegionsByOwner(ownerKey, true).stream()
            .map(region -> BeanUtils.copyProperties(region, PublicFloodRegionVO.class))
            .collect(java.util.stream.Collectors.toList());
    return CommonResult.data(regions);
  }

  @Operation(summary = "公共获取当前匿名会话的区域列表")
  @GetMapping("/list")
  public CommonResult<List<PublicFloodRegionVO>> listRegions(@RequestParam String sessionId) throws Exception {
    String ownerKey = publicAccessService.publicOwnerKey(sessionId);
    List<PublicFloodRegionVO> regions = floodRegionService.listRegionsByOwner(ownerKey, false).stream()
            .map(region -> BeanUtils.copyProperties(region, PublicFloodRegionVO.class))
            .collect(java.util.stream.Collectors.toList());
    return CommonResult.data(regions);
  }

  @Operation(summary = "公共初始化当前匿名会话的区域")
  @PostMapping("/initialize")
  public CommonResult<String> initializeRegion(
          @RequestPart("sessionId") String sessionId,
          @RequestPart("bo") FloodRegionSaveBO bo,
          @RequestPart("nodeShpFiles") MultipartFile[] nodeShpFiles,
          @RequestPart("linkShpFiles") MultipartFile[] linkShpFiles,
          @RequestPart("ascFile") MultipartFile ascFile,
          @RequestPart("prjFile") MultipartFile prjFile,
          @RequestPart(value = "landuseFile", required = false) MultipartFile landuseFile,
          @RequestPart(value = "landusePrjFile", required = false) MultipartFile landusePrjFile,
          @RequestPart(value = "adaptiveMeshShpFiles", required = false) MultipartFile[] adaptiveMeshShpFiles) throws Exception {
    String ownerKey = publicAccessService.publicOwnerKey(sessionId);
    return CommonResult.data(floodRegionService.initializePublicRegion(bo, nodeShpFiles, linkShpFiles,
            ascFile, prjFile, landuseFile, landusePrjFile, adaptiveMeshShpFiles, ownerKey));
  }

  @Operation(summary = "公共删除当前匿名会话的区域")
  @DeleteMapping("/delete")
  public CommonResult<Boolean> deleteRegion(@RequestParam String regionId,
                                            @RequestParam String sessionId) throws Exception {
    String ownerKey = publicAccessService.publicOwnerKey(sessionId);
    return CommonResult.data(floodRegionService.deleteRegionByOwner(regionId, ownerKey));
  }

  @Operation(summary = "公共基于区域进行城市洪涝模拟")
  @PostMapping("/simulate")
  public CommonResult<String> simulateWithRegion(
          @RequestPart("bo") UrbanFloodSimulationBO bo,
          @RequestPart("csvFile") MultipartFile csvFile) throws Exception {
    publicAccessService.validateSessionId(bo.getSessionId());
    floodRegionService.getRegionDetailByOwner(
            bo.getRegionId(), publicAccessService.publicOwnerKey(bo.getSessionId()));
    return CommonResult.data(urbanFloodService.simulateWithRegion(bo, csvFile));
  }
}
