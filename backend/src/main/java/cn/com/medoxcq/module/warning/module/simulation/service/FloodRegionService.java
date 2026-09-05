package cn.com.medoxcq.module.warning.module.simulation.service;

import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.FloodRegionSaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.FloodRegionVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 *
 * @author system
 * @since 2025/12/11
 */
public interface FloodRegionService {
    String initializeRegion(FloodRegionSaveBO bo,
                           MultipartFile[] nodeShpFiles,
                           MultipartFile[] linkShpFiles,
                           MultipartFile ascFile,
                           MultipartFile prjFile,
                           MultipartFile landuseFile,
                           MultipartFile landusePrjFile,
                           MultipartFile[] adaptiveMeshShpFiles) throws Exception;

    String initializePublicRegion(FloodRegionSaveBO bo,
                                  MultipartFile[] nodeShpFiles,
                                  MultipartFile[] linkShpFiles,
                                  MultipartFile ascFile,
                                  MultipartFile prjFile,
                                  MultipartFile landuseFile,
                                  MultipartFile landusePrjFile,
                                  MultipartFile[] adaptiveMeshShpFiles,
                                  String ownerKey) throws Exception;

    List<FloodRegionVO> listRegionsByOwner(String ownerKey, boolean initializedOnly) throws Exception;

    FloodRegionVO getRegionDetailByOwner(String regionId, String ownerKey) throws Exception;

    boolean deleteRegionByOwner(String regionId, String ownerKey) throws Exception;
    boolean updateRegion(FloodRegionSaveBO bo) throws Exception;
    boolean deleteRegion(String regionId) throws Exception;
    FloodRegionVO getRegionDetail(String regionId) throws Exception;
    List<FloodRegionVO> listRegions() throws Exception;
    List<FloodRegionVO> listInitializedRegions() throws Exception;
}
