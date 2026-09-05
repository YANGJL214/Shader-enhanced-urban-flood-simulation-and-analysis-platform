package cn.com.medoxcq.module.warning.module.simulation.service.impl;

import cn.com.medoxcq.common.exception.BusinessException;
import cn.com.medoxcq.common.exception.enums.ErrorCodeEnum;
import cn.com.medoxcq.common.pojo.vo.CommonPageVO;
import cn.com.medoxcq.common.util.AssertUtils;
import cn.com.medoxcq.module.warning.config.SimulationConfiguration;
import cn.com.medoxcq.module.warning.module.rule.emq.MqttMessagePublisher;
import cn.com.medoxcq.module.warning.module.simulation.entity.ModelSimulationHistory;
import cn.com.medoxcq.module.warning.module.simulation.enums.ModelNameEnum;
import cn.com.medoxcq.module.warning.module.simulation.enums.SimulationStatusEnum;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.ModelSimulationHistoryQueryBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.ModelSimulationHistorySaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.UrbanFloodSaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.ModelSimulationHistoryVO;
import cn.com.medoxcq.module.warning.module.simulation.service.ModelSimulationHistoryService;
import cn.com.medoxcq.module.warning.module.simulation.service.UrbanFloodService;
import cn.com.medoxcq.module.warning.module.simulation.utils.UrbanFloodFileManager;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFiles;
import ucar.nc2.Variable;
import ucar.ma2.Array;
import ucar.ma2.Index;
import ucar.ma2.IndexIterator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.gce.arcgrid.ArcGridReader;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder;
import org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;

/**
 *
 * @author system
 * @since 2025/9/17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrbanFloodServiceImpl implements UrbanFloodService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String BUILDING_WHITE_MODEL_FILE = "building_white_models.json";
    private static final double DEFAULT_BUILDING_WHITE_MODEL_HEIGHT = 18.0;
    private static final Pattern MODEL_TIME_PATTERN = Pattern.compile("Time\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final double MODEL_COMPUTE_PROGRESS_START = 5.0;
    private static final double MODEL_COMPUTE_PROGRESS_END = 85.0;
    private static final long MODEL_PROGRESS_MIN_INTERVAL_MILLIS = 1000L;

    // private final WebSocket webSocket;
    private final MqttMessagePublisher publisher;
    private static final String TOPIC_TEMPLATE = "/simulate/result/%s";
    private final ModelSimulationHistoryService historyService;
    private final UrbanFloodFileManager urbanFloodFileManager;
    private final SimulationConfiguration simulationProperties;
    
    private final Map<String, Boolean> hasModelOutputMap = new java.util.concurrent.ConcurrentHashMap<>();
    
    private final Map<String, Boolean> hasSuccessMarkerMap = new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<String, Double> totalSimulationSecondsMap = new ConcurrentHashMap<>();
    private final Map<String, Double> lastPublishedProgressMap = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProgressPublishTimeMap = new ConcurrentHashMap<>();
    private final Set<String> postProcessingSimulationIds = ConcurrentHashMap.newKeySet();
    private final Set<String> resultProcessingSimulationIds = ConcurrentHashMap.newKeySet();

    private void initializeProgressTracking(String simulationId, Double totalSeconds) {
        double safeTotalSeconds = totalSeconds == null || totalSeconds <= 0 ? 1.0 : totalSeconds;
        totalSimulationSecondsMap.put(simulationId, safeTotalSeconds);
        lastPublishedProgressMap.put(simulationId, 0.0);
        lastProgressPublishTimeMap.put(simulationId, 0L);
        postProcessingSimulationIds.remove(simulationId);
        resultProcessingSimulationIds.remove(simulationId);
    }

    private void clearProgressTracking(String simulationId) {
        totalSimulationSecondsMap.remove(simulationId);
        lastPublishedProgressMap.remove(simulationId);
        lastProgressPublishTimeMap.remove(simulationId);
        postProcessingSimulationIds.remove(simulationId);
    }

    private void publishProgress(String topic, String simulationId, String stage, double progress,
                                 String message, Double modelElapsedSeconds) {
        try {
            double safeProgress = Math.max(0.0, Math.min(100.0, progress));
            double effectiveProgress = Math.max(lastPublishedProgressMap.getOrDefault(simulationId, 0.0), safeProgress);
            lastPublishedProgressMap.put(simulationId, effectiveProgress);

            Map<String, Object> progressMessage = new HashMap<>();
            progressMessage.put("simulationId", simulationId);
            progressMessage.put("type", "PROGRESS");
            progressMessage.put("status", "RUNNING");
            progressMessage.put("stage", stage);
            progressMessage.put("progress", effectiveProgress);
            progressMessage.put("message", message);

            if (modelElapsedSeconds != null) {
                double totalSeconds = totalSimulationSecondsMap.getOrDefault(simulationId, 1.0);
                progressMessage.put("modelElapsedSeconds", modelElapsedSeconds);
                progressMessage.put("modelTotalSeconds", totalSeconds);
                progressMessage.put("modelProgress", Math.min(100.0, modelElapsedSeconds / totalSeconds * 100.0));
            }

            publisher.publishSimulationResult(topic, JSON_MAPPER.writeValueAsString(progressMessage));
        } catch (Exception e) {
            log.warn("推送模拟进度失败: simulationId={}, stage={}, error={}", simulationId, stage, e.getMessage());
        }
    }

    private void publishModelTimeProgress(String topic, String simulationId, String outputLine) {
        if (postProcessingSimulationIds.contains(simulationId) || resultProcessingSimulationIds.contains(simulationId)) {
            return;
        }

        Matcher matcher = MODEL_TIME_PATTERN.matcher(outputLine);
        if (!matcher.find()) {
            return;
        }

        try {
            double elapsedSeconds = Double.parseDouble(matcher.group(1));
            double totalSeconds = totalSimulationSecondsMap.getOrDefault(simulationId, 1.0);
            double modelProgress = Math.min(100.0, elapsedSeconds / totalSeconds * 100.0);
            double overallProgress = MODEL_COMPUTE_PROGRESS_START +
                    modelProgress / 100.0 * (MODEL_COMPUTE_PROGRESS_END - MODEL_COMPUTE_PROGRESS_START);
            double previousProgress = lastPublishedProgressMap.getOrDefault(simulationId, 0.0);
            long now = System.currentTimeMillis();
            long lastPublishTime = lastProgressPublishTimeMap.getOrDefault(simulationId, 0L);

            if (overallProgress - previousProgress < 1.0 && now - lastPublishTime < MODEL_PROGRESS_MIN_INTERVAL_MILLIS) {
                return;
            }

            lastProgressPublishTimeMap.put(simulationId, now);
            publishProgress(topic, simulationId, "MODEL_RUNNING", overallProgress,
                    "ANUGA-SWMM 耦合计算中", elapsedSeconds);
        } catch (NumberFormatException e) {
            log.debug("无法解析模型计算时间: {}", outputLine);
        }
    }

    private boolean isFinalSuccessMarker(String outputLine) {
        return outputLine != null && "[SUCCESS] Model execution completed successfully.".equals(outputLine.trim());
    }
    private void startResultProcessingOnce(String simulationId, UrbanFloodFileManager.ProjectWorkspace workspace,
                                            String topic, String trigger) {
        if (!resultProcessingSimulationIds.add(simulationId)) {
            log.debug("模拟 {} 的结果处理已启动，忽略重复触发: {}", simulationId, trigger);
            return;
        }

        postProcessingSimulationIds.add(simulationId);
        hasModelOutputMap.remove(simulationId);
        hasSuccessMarkerMap.remove(simulationId);
        publishProgress(topic, simulationId, "POST_PROCESSING", 86.0,
                "检测到模型成功标记，正在加载结果", null);

        CompletableFuture.runAsync(() -> {
            processSimulationResults(simulationId, workspace, topic);
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            resultProcessingSimulationIds.remove(simulationId);
        });
    }

    @Override
        public String simulation(UrbanFloodSaveBO bo,
                     MultipartFile[] nodeShpFiles,
                     MultipartFile[] linkShpFiles,
                     MultipartFile ascFile,
                     MultipartFile prjFile,
                     MultipartFile csvFile,
                     MultipartFile landuseFile) throws Exception {

        AssertUtils.isTrue((nodeShpFiles == null || nodeShpFiles.length == 0 || 
                           linkShpFiles == null || linkShpFiles.length == 0 ||
                           ascFile.isEmpty() || prjFile.isEmpty() || csvFile.isEmpty() || landuseFile.isEmpty()),
            "缺少必要的输入文件");

        ModelSimulationHistoryQueryBO queryBO = new ModelSimulationHistoryQueryBO();
        queryBO.setModelName(ModelNameEnum.ANUGA_SWMM);
        queryBO.setState(SimulationStatusEnum.RUNNING);
        List<ModelSimulationHistory> histories = historyService.queryRunningModel(queryBO);
        if (CollectionUtil.isNotEmpty(histories)) {
            throw new BusinessException(ErrorCodeEnum.MODEL_RUNNING);
        }

        ModelSimulationHistorySaveBO historySaveBO = new ModelSimulationHistorySaveBO();
        historySaveBO.setState(SimulationStatusEnum.RUNNING.getCode());
        historySaveBO.setSimulationName(bo.getProjectName());
        historySaveBO.setModelName(ModelNameEnum.ANUGA_SWMM.getCode());
        historySaveBO.setSaveData(bo.getSaveData());
        String simulationId = historyService.save(historySaveBO);
        historySaveBO.setId(simulationId);

        // Object loginId = StpUtil.getLoginId();
        // if (ObjectUtil.isNull(loginId)) {
        // }
        String topic = String.format(TOPIC_TEMPLATE, bo.getSessionId());

        try {
            log.info("开始创建项目工作空间...");
                UrbanFloodFileManager.ProjectWorkspace workspace =
                    urbanFloodFileManager.createProjectWorkspace(bo.getProjectName(), bo, nodeShpFiles, linkShpFiles, ascFile, prjFile, csvFile, landuseFile);
            
            ModelSimulationHistorySaveBO workspaceUpdateBO = new ModelSimulationHistorySaveBO();
            workspaceUpdateBO.setId(simulationId);
            workspaceUpdateBO.setWorkspacePath(workspace.getProjectDir());
            historyService.update(workspaceUpdateBO);
            log.info("工作空间路径已保存: {}", workspace.getProjectDir());
            
                log.info("开始根据Shapefile和DEM生成管网信息文件...");
                SwmmInpGenerator generator = new SwmmInpGenerator();
                generator.applyCustomMappings(
                    bo.getNodeFieldMapping(),
                    bo.getLinkFieldMapping(),
                    bo.getNodeTypeMapping(),
                    bo.getShapeTypeMapping());
                List<String> nodeShps = Collections.singletonList(workspace.getNodeShpPath());
                List<String> linkShps = Collections.singletonList(workspace.getLinkShpPath());
                    generator.setSpatialTolerance(0.05);
                    generator.setUseDemForDepth(true);
                    Integer simDuration = bo.getSimulationDuration() != null ? 
                        bo.getSimulationDuration().intValue() : null;
                    boolean inpGenerated = generator.generateInpFile(nodeShps,
                        linkShps,
                        workspace.getInpFilePath(),
                        workspace.getAscFilePath(),
                        workspace.getCsvFilePath(),
                        simDuration);
                if (!inpGenerated) {
                throw new BusinessException(ErrorCodeEnum.MODEL_RUNNING.getCode(), "生成INP文件失败");
                }
                log.info("管网信息文件生成成功: {}", workspace.getInpFilePath());

            //3.inp --> bci.csv
            log.info("开始解析INP文件并生成BCI.CSV...");
            Path bciCsvPath = generateBciCsv(Paths.get(workspace.getInpFilePath()),
                    Paths.get(workspace.getProjectDir(), "bci.csv"));
            log.info("已生成bci.csv: {}", bciCsvPath.toString());

            log.info("开始处理ASC文件并生成TIN CSV...");

            TinCsvPaths tinCsvPaths = generateTinFromDem(ascFile, workspace.getProjectDir());
            log.info("DEM转换完成，CSV文件保存至: poi={}, tri={}", tinCsvPaths.getPoiCsvPath(), tinCsvPaths.getTriCsvPath());

            log.info("生成YAML配置文件...");
            generateConfigYaml(ascFile, csvFile, landuseFile, workspace, bo);
            log.info("配置文件已生成完成");

            log.info("生成模型启动脚本...");
            String generatedYamlPath = Paths.get(workspace.getProjectDir(), "generated_config.yaml").toString();
            modifyBatFile(generatedYamlPath, workspace.getInpFilePath(), workspace);
            log.info("模型启动脚本生成完成");

            String batPath = workspace.getBatFilePath();
            if (batPath == null || batPath.isEmpty()) {
                batPath = Paths.get(workspace.getProjectDir(), "run_simulation.bat").toString();
            }

            log.info("启动ANUGA-SWMM耦合模型计算...");
            
            hasModelOutputMap.put(simulationId, false);
            hasSuccessMarkerMap.put(simulationId, false);
            initializeProgressTracking(simulationId, bo.getSimulationDuration());
            
            UrbanFloodFileManager.OutputCallback outputCallback = (outputLine) -> {
                try {
                    hasModelOutputMap.put(simulationId, true);
                    
                    if (outputLine.contains("Writing output report") || 
                        outputLine.contains("[SIMULATION_SUCCESS]") ||
                        outputLine.contains("[SUCCESS]")) {
                        hasSuccessMarkerMap.put(simulationId, true);
                        log.info("检测到模拟成功标记: {}", outputLine);
                    }
                    publishModelTimeProgress(topic, simulationId, outputLine);
                    if (isFinalSuccessMarker(outputLine)) {
                        startResultProcessingOnce(simulationId, workspace, topic, "最终成功标记");
                    }
                    
                    Map<String, Object> outputMessage = new HashMap<>();
                    outputMessage.put("simulationId", simulationId);
                    outputMessage.put("type", "MODEL_OUTPUT");
                    outputMessage.put("message", outputLine);
                    outputMessage.put("timestamp", System.currentTimeMillis());
                    // webSocket.sendMessage(new ObjectMapper().writeValueAsString(outputMessage), loginId);
                    publisher.publishSimulationResult(topic, new ObjectMapper().writeValueAsString(outputMessage));
                } catch (Exception e) {
                    log.error("推送模型输出消息失败: {}", e.getMessage());
                }
            };
            
            Process modelProcess = urbanFloodFileManager.executeSimulation(batPath, outputCallback);

            // final Object finalLoginId = loginId;
            CompletableFuture.runAsync(() -> monitorModelExecution(simulationId, modelProcess, workspace, topic));

            Map<String, Object> statusMessage = new HashMap<>();
            statusMessage.put("simulationId", simulationId);
            statusMessage.put("type", "STATUS");
            statusMessage.put("status", "RUNNING");
            statusMessage.put("message", "ANUGA-SWMM城市洪涝模拟已启动");
            statusMessage.put("stage", "MODEL_STARTING");
            statusMessage.put("progress", MODEL_COMPUTE_PROGRESS_START);
            statusMessage.put("modelTotalSeconds", totalSimulationSecondsMap.get(simulationId));
            // webSocket.sendMessage(new ObjectMapper().writeValueAsString(statusMessage), loginId);
            publisher.publishSimulationResult(topic, new ObjectMapper().writeValueAsString(statusMessage));

            return simulationId;

        } catch (Exception e) {
            log.error("城市洪涝模拟执行失败: {}", e.getMessage(), e);
            
            ModelSimulationHistorySaveBO updateBO = new ModelSimulationHistorySaveBO();
            updateBO.setId(simulationId);
            updateBO.setState(SimulationStatusEnum.FAILED.getCode());
            historyService.update(updateBO);
            
            throw e;
        }
    }
    private void monitorModelExecution(String simulationId, Process process,
                                     UrbanFloodFileManager.ProjectWorkspace workspace, String topic) {
        try {
            Timer timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    checkAndUpdateProgress(simulationId, process, workspace, timer, topic);
                }
            }, 2000, 3000);
            
            try {
                process.waitFor();
                log.info("模拟 {} 进程已结束，等待200ms让输出处理完成", simulationId);
                Thread.sleep(200);
                checkAndUpdateProgress(simulationId, process, workspace, timer, topic);
            } catch (InterruptedException e) {
                log.warn("等待进程结束时被中断: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }

        } catch (Exception e) {
            log.error("监控模型执行状态失败: {}", e.getMessage(), e);
        }
    }
    private void checkAndUpdateProgress(String simulationId, Process process,
                                      UrbanFloodFileManager.ProjectWorkspace workspace, Timer timer, String topic) {
        // if (ObjectUtil.isNull(loginId)) {
        //     return;
        // }

        try {
            boolean hasSuccessMarker = hasSuccessMarkerMap.getOrDefault(simulationId, false);
            
            boolean isAlive = process.isAlive();
            int exitCode = isAlive ? -1 : process.exitValue();
            
            log.debug("模拟 {} 状态检查: 进程存活={}, 退出码={}, 成功标记={}", 
                simulationId, isAlive, exitCode, hasSuccessMarker);
            
            UrbanFloodFileManager.ModelRunStatus status = 
                urbanFloodFileManager.checkSimulationStatus(process, workspace.getOutputDir(), hasSuccessMarker);

            Boolean hasOutput = hasModelOutputMap.get(simulationId);
            if (hasOutput == null || !hasOutput) {
                publishProgress(topic, simulationId, "MODEL_STARTING", MODEL_COMPUTE_PROGRESS_START,
                        "正在等待模型输出", null);
            }

            if (status.isCompleted()) {
                log.info("模拟 {} 已完成: 状态={}, 消息={}", 
                    simulationId, status.getStatus(), status.getMessage());
                timer.cancel();
                hasModelOutputMap.remove(simulationId);
                hasSuccessMarkerMap.remove(simulationId);
                
                if (status.isSuccess()) {
                    startResultProcessingOnce(simulationId, workspace, topic, "进程状态检测");
                } else {
                    log.warn("模拟 {} 失败: {}", simulationId, status.getMessage());
                    handleSimulationFailure(simulationId, status.getMessage(), topic);
                }
            } else {
                log.debug("模拟 {} 仍在运行或等待完成: {}", simulationId, status.getMessage());
            }

        } catch (Exception e) {
            log.error("检查模型执行状态失败: {}", e.getMessage(), e);
            timer.cancel();
            hasModelOutputMap.remove(simulationId);
            hasSuccessMarkerMap.remove(simulationId);
            handleSimulationFailure(simulationId, "监控执行状态时发生错误: " + e.getMessage(), topic);
        }
    }
    private void processSimulationResults(String simulationId, UrbanFloodFileManager.ProjectWorkspace workspace, String topic) {
        // if (ObjectUtil.isNull(loginId)) {
        //     return;
        // }

        try {
            log.info("开始处理城市洪涝模拟结果...");

            Path outputDir = Paths.get(workspace.getOutputDir());
            List<String> resultFiles = new ArrayList<>();

            Files.walk(outputDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".asc") || p.toString().endsWith(".csv") ||
                             p.toString().endsWith(".sww") || p.toString().endsWith(".out"))
                    .forEach(p -> resultFiles.add(p.toString()));

            if (resultFiles.isEmpty()) {
                throw new Exception("未找到模拟结果文件");
            }

            Map<String, Object> resultData = new HashMap<>();
            for (String filePath : resultFiles) {
                Path file = Paths.get(filePath);
                String fileName = file.getFileName().toString();

                if (filePath.endsWith(".sww")) {
                    log.info("发现SWW文件: {}, 开始后处理...", fileName);
                    try {
                        publishProgress(topic, simulationId, "SWW_PROCESSING", 93.0,
                                "正在生成水体结果", null);
                        Map<String, Object> swwResult = processSWWFile(file, workspace.getOutputDir(),
                                (processedFrames, totalFrames) -> {
                                    double frameRatio = totalFrames <= 0 ? 1.0 : (double) processedFrames / totalFrames;
                                    double progress = 93.0 + frameRatio * 5.0;
                                    publishProgress(topic, simulationId, "SWW_PROCESSING", progress,
                                            String.format("正在生成水体结果（%d/%d 帧）", processedFrames, totalFrames), null);
                                });
                        resultData.put("swwData", swwResult);
                        log.info("SWW文件处理完成: {}", fileName);
                        publishProgress(topic, simulationId, "SWW_PROCESSING", 98.0,
                                "水体结果生成完成", null);
                    } catch (Exception e) {
                        log.error("处理SWW文件失败: {}", e.getMessage(), e);
                        resultData.put("swwError", "SWW处理失败: " + e.getMessage());
                    }

                } else if (filePath.endsWith(".out")) {
                    publishProgress(topic, simulationId, "SWMM_PROCESSING", 88.0,
                            "正在解析 SWMM 管网结果", null);
                    Map<String, Object> outFileData = processOutFile(file);
                    resultData.put("simulationResults", outFileData);
                    publishProgress(topic, simulationId, "SWMM_PROCESSING", 92.0,
                            "SWMM 管网结果解析完成", null);
                }
            }

            try {
                if (workspace.getInpFilePath() != null) {
                    Path inpPath = Paths.get(workspace.getInpFilePath());
                    if (Files.exists(inpPath)) {
                        String inpContent = new String(Files.readAllBytes(inpPath), StandardCharsets.UTF_8);
                        resultData.put("inpContent", inpContent);
                        resultData.put("inpFileName", inpPath.getFileName().toString());
                        resultData.put("inpFilePath", inpPath.toString());
                    }
                }
            } catch (Exception e) {
                log.warn("读取INP文件内容失败: {}", e.getMessage());
            }

            addBuildingWhiteModelData(resultData, Paths.get(workspace.getProjectDir()));

            ModelSimulationHistorySaveBO updateBO = new ModelSimulationHistorySaveBO();
            updateBO.setId(simulationId);
            updateBO.setState(SimulationStatusEnum.SUCCEED.getCode());
            historyService.update(updateBO);

            Map<String, Object> completionMessage = new HashMap<>();
            completionMessage.put("simulationId", simulationId);
            completionMessage.put("type", "COMPLETION");
            completionMessage.put("status", "SUCCESS");
            completionMessage.put("message", "ANUGA-SWMM城市洪涝模拟完成");
            completionMessage.put("progress", 100);
            completionMessage.put("stage", "RESULT_READY");
            completionMessage.put("resultData", resultData);
            // webSocket.sendMessage(new ObjectMapper().writeValueAsString(completionMessage), loginId);
            publisher.publishSimulationResult(topic, new ObjectMapper().writeValueAsString(completionMessage));
            clearProgressTracking(simulationId);

            log.info("城市洪涝模拟结果处理完成: {}", simulationId);

        } catch (Exception e) {
            log.error("处理模拟结果失败: {}", e.getMessage(), e);
            handleSimulationFailure(simulationId, "处理结果时发生错误: " + e.getMessage(), topic);
        }
    }
    private void handleSimulationFailure(String simulationId, String errorMessage, String topic) {
        // if (ObjectUtil.isNull(loginId)) {
        // }

        try {
            ModelSimulationHistorySaveBO updateBO = new ModelSimulationHistorySaveBO();
            updateBO.setId(simulationId);
            updateBO.setState(SimulationStatusEnum.FAILED.getCode());
            historyService.update(updateBO);

            Map<String, Object> failureMessage = new HashMap<>();
            failureMessage.put("simulationId", simulationId);
            failureMessage.put("type", "ERROR");
            failureMessage.put("status", "FAILED");
            failureMessage.put("message", errorMessage);
            failureMessage.put("stage", "FAILED");
            failureMessage.put("progress", lastPublishedProgressMap.getOrDefault(simulationId, 0.0));
            // webSocket.sendMessage(new ObjectMapper().writeValueAsString(failureMessage), loginId);
            publisher.publishSimulationResult(topic, new ObjectMapper().writeValueAsString(failureMessage));
            clearProgressTracking(simulationId);

        } catch (Exception e) {
            log.error("处理模拟失败状态时发生错误: {}", e.getMessage(), e);
        }
    }



    @Override
    public ModelSimulationHistoryVO getSimulationResult(String simulationId) throws Exception {
        return historyService.detail(simulationId);
    }

    @Override
    public List<ModelSimulationHistoryVO> getSimulationHistory(String projectName) throws Exception {
        ModelSimulationHistoryQueryBO queryBO = new ModelSimulationHistoryQueryBO();
        queryBO.setModelName(ModelNameEnum.ANUGA_SWMM);

        CommonPageVO<ModelSimulationHistoryVO> pageResult = historyService.page(queryBO);
        
        return pageResult.getRecords();
    }

    @Override
    public void stopSimulation(String simulationId) throws Exception {
        hasModelOutputMap.remove(simulationId);
        hasSuccessMarkerMap.remove(simulationId);
        
        ModelSimulationHistorySaveBO updateBO = new ModelSimulationHistorySaveBO();
        updateBO.setId(simulationId);
        updateBO.setState(SimulationStatusEnum.FAILED.getCode());
        historyService.update(updateBO);

        // Map<String, Object> stopMessage = new HashMap<>();
        // stopMessage.put("simulationId", simulationId);
        // stopMessage.put("type", "STOP");
        // stopMessage.put("status", "STOPPED");
        //
        // Object loginId = StpUtil.getLoginId();
        // if (ObjectUtil.isNull(loginId)) {
        // }
        // webSocket.sendMessage(new ObjectMapper().writeValueAsString(stopMessage), loginId);
    }
    public Map<String, Object> loadHistoryResult(String simulationId) throws Exception {
        log.info("========== 开始加载历史模拟结果 ==========");
        log.info("模拟ID: {}", simulationId);
        
        ModelSimulationHistoryVO history = historyService.detail(simulationId);
        if (history == null) {
            throw new BusinessException(ErrorCodeEnum.DATA_NOT_EXIST.getCode(), "模拟记录不存在");
        }
        
        if (history.getState() != SimulationStatusEnum.SUCCEED) {
            throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), 
                "只能加载成功完成的模拟结果，当前状态: " + history.getState().getDesc());
        }
        
        String workspacePath = history.getWorkspacePath();
        if (workspacePath == null || workspacePath.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), 
                "模拟记录中未保存工作空间路径");
        }
        
        Path workspaceDir = Paths.get(workspacePath);
        if (!Files.exists(workspaceDir) || !Files.isDirectory(workspaceDir)) {
            throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), 
                "工作空间目录不存在: " + workspacePath);
        }
        
        log.info("工作空间路径: {}", workspacePath);
        
        Map<String, Object> resultData = new HashMap<>();
        
        Path swwFramesDir = workspaceDir.resolve("sww_frames");
        if (Files.exists(swwFramesDir) && Files.isDirectory(swwFramesDir)) {
            log.info("加载SWW图像帧数据: {}", swwFramesDir);
            try {
                Map<String, Object> swwData = loadSWWFramesFromDirectory(swwFramesDir);
                resultData.put("swwData", swwData);
                log.info("SWW数据加载成功，共{}帧", swwData.get("processedFrames"));
            } catch (Exception e) {
                log.error("加载SWW数据失败: {}", e.getMessage(), e);
                resultData.put("swwError", "SWW数据加载失败: " + e.getMessage());
            }
        } else {
            log.warn("未找到SWW图像帧目录: {}", swwFramesDir);
        }
        
        if (Files.exists(workspaceDir) && Files.isDirectory(workspaceDir)) {
            log.info("加载SWMM JSON数据: {}", workspaceDir);
            try {
                Map<String, Object> swmmData = loadSWMMDataFromDirectory(workspaceDir);
                resultData.put("simulationResults", swmmData);
                log.info("SWMM数据加载成功");
            } catch (Exception e) {
                log.error("加载SWMM数据失败: {}", e.getMessage(), e);
                resultData.put("swmmError", "SWMM数据加载失败: " + e.getMessage());
            }
        } else {
            log.warn("未找到SWMM输出目录: {}", workspaceDir);
        }

        try (java.util.stream.Stream<Path> stream = Files.list(workspaceDir)) {
            Optional<Path> inpOpt = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".inp"))
                    .findFirst();
            if (inpOpt.isPresent()) {
                Path inpPath = inpOpt.get();
                String inpContent = new String(Files.readAllBytes(inpPath), StandardCharsets.UTF_8);
                resultData.put("inpContent", inpContent);
                resultData.put("inpFileName", inpPath.getFileName().toString());
                resultData.put("inpFilePath", inpPath.toString());
                log.info("已加载INP文件: {}", inpPath);
            } else {
                log.warn("工作空间中未找到INP文件");
            }
        } catch (Exception e) {
            log.warn("加载INP文件失败: {}", e.getMessage());
        }

        addBuildingWhiteModelData(resultData, workspaceDir, history);

        if (resultData.isEmpty() ||
            (!resultData.containsKey("swwData") && !resultData.containsKey("simulationResults"))) {
            throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(),
                "未找到任何已处理的模拟结果数据");
        }

        log.info("========== 历史模拟结果加载完成 ==========");
        return resultData;
    }

    private Map<String, Object> prepareBuildingWhiteModelData(cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion region,
                                                              Path workspaceDir) {
        try {
            Map<String, Object> data = buildBuildingWhiteModelData(region);
            if (!hasBuildingWhiteModelData(data)) {
                return data;
            }

            Path outputPath = workspaceDir.resolve(BUILDING_WHITE_MODEL_FILE);
            Files.write(outputPath,
                    JSON_MAPPER.writeValueAsString(data).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.info("建筑白模数据已生成: {}, features={}", outputPath, ((List<?>) data.get("features")).size());
            return data;
        } catch (Exception e) {
            log.warn("生成建筑白模数据失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private void addBuildingWhiteModelData(Map<String, Object> resultData, Path workspaceDir) {
        try {
            Path jsonPath = workspaceDir.resolve(BUILDING_WHITE_MODEL_FILE);
            if (!Files.exists(jsonPath) || !Files.isRegularFile(jsonPath)) {
                return;
            }

            Map<String, Object> data = JSON_MAPPER.readValue(jsonPath.toFile(), Map.class);
            if (hasBuildingWhiteModelData(data)) {
                resultData.put("buildingModelData", data);
                log.info("建筑白模数据已加入结果: {}", jsonPath);
            }
        } catch (Exception e) {
            log.warn("加载建筑白模数据失败: {}", e.getMessage());
        }
    }

    private void addBuildingWhiteModelData(Map<String, Object> resultData,
                                           Path workspaceDir,
                                           ModelSimulationHistoryVO history) {
        addBuildingWhiteModelData(resultData, workspaceDir);
        if (resultData.containsKey("buildingModelData")) {
            return;
        }

        String regionId = extractRegionId(history == null ? null : history.getSimulationParams());
        if (regionId == null || regionId.trim().isEmpty()) {
            return;
        }

        try {
            cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion region =
                    urbanFloodFileManager.getRegionById(regionId);
            Map<String, Object> data = prepareBuildingWhiteModelData(region, workspaceDir);
            if (hasBuildingWhiteModelData(data)) {
                resultData.put("buildingModelData", data);
            }
        } catch (Exception e) {
            log.warn("Failed to rebuild building white model data for history: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractRegionId(String simulationParams) {
        if (simulationParams == null || simulationParams.trim().isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> params = JSON_MAPPER.readValue(simulationParams, Map.class);
            Object regionId = params.get("regionId");
            return regionId == null ? null : String.valueOf(regionId);
        } catch (Exception e) {
            log.warn("Failed to parse simulation params for region id: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasBuildingWhiteModelData(Map<String, Object> data) {
        if (data == null) {
            return false;
        }
        Object features = data.get("features");
        return features instanceof List && !((List<?>) features).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildBuildingWhiteModelData(cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion region) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "FeatureCollection");
        result.put("name", "urban_flood_building_white_models");
        result.put("defaultHeight", DEFAULT_BUILDING_WHITE_MODEL_HEIGHT);
        result.put("features", new ArrayList<Map<String, Object>>());

        if (region == null || region.getDataPath() == null) {
            return result;
        }

        Path regionDataPath = Paths.get(region.getDataPath());
        Path meshDir = regionDataPath.resolve("adaptive_mesh_shp");
        if (!Files.isDirectory(meshDir)) {
            log.info("No adaptive mesh shapefile directory for building white models: {}", meshDir);
            return result;
        }

        Set<Path> shpPaths = new LinkedHashSet<>();
        collectBuildingShpPathsFromRegionConfig(meshDir, region.getConfigParams(), shpPaths);
        collectBuildingShpPathsFromTinConfig(regionDataPath, meshDir, shpPaths);
        if (shpPaths.isEmpty()) {
            collectLikelyBuildingShps(meshDir, shpPaths, true);
        }

        if (shpPaths.isEmpty()) {
            log.info("No building shapefile found for building white models, regionDataPath={}", regionDataPath);
            return result;
        }

        List<Map<String, Object>> features = (List<Map<String, Object>>) result.get("features");
        for (Path shpPath : shpPaths) {
            appendBuildingFeatures(features, shpPath);
        }
        log.info("Building white model source files: {}, generated features={}", shpPaths.size(), features.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collectBuildingShpPathsFromRegionConfig(Path meshDir, String configParamsJson, Set<Path> shpPaths) {
        if (configParamsJson == null || configParamsJson.trim().isEmpty()) {
            return;
        }

        try {
            Map<String, Object> configParams = JSON_MAPPER.readValue(configParamsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object adaptiveFeaturesObj = configParams.get("adaptiveMeshFeatures");
            if (!(adaptiveFeaturesObj instanceof List)) {
                return;
            }

            int unresolvedBuildingConfigs = 0;
            for (Object item : (List<?>) adaptiveFeaturesObj) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> featureConfig = (Map<String, Object>) item;
                if (!isBuildingFeatureConfig(featureConfig)) {
                    continue;
                }

                Object fileNamesObj = featureConfig.get("fileNames");
                if (!(fileNamesObj instanceof List)) {
                    unresolvedBuildingConfigs++;
                    continue;
                }

                boolean resolved = false;
                for (Object nameObj : (List<?>) fileNamesObj) {
                    String fileName = safeFileName(String.valueOf(nameObj));
                    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".shp")) {
                        continue;
                    }
                    Path shpPath = resolveAdaptiveMeshShp(meshDir, fileName);
                    if (shpPath != null) {
                        shpPaths.add(shpPath);
                        resolved = true;
                    }
                }
                if (!resolved) {
                    unresolvedBuildingConfigs++;
                }
            }

            if (unresolvedBuildingConfigs > 0) {
                log.info("Building white model region config has unresolved building shp entries: {}", unresolvedBuildingConfigs);
            }
        } catch (Exception e) {
            log.warn("Failed to parse building white model region config: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void collectBuildingShpPathsFromTinConfig(Path regionDataPath, Path meshDir, Set<Path> shpPaths) {
        Path configPath = regionDataPath.resolve("adaptive_tin_config.json");
        if (!Files.exists(configPath) || !Files.isRegularFile(configPath)) {
            return;
        }

        try {
            Map<String, Object> tinConfig = JSON_MAPPER.readValue(configPath.toFile(), Map.class);
            Object featuresObj = tinConfig.get("features");
            if (!(featuresObj instanceof List)) {
                return;
            }

            int buildingConfigCount = 0;
            int unresolvedPathCount = 0;
            for (Object item : (List<?>) featuresObj) {
                if (!(item instanceof Map)) {
                    continue;
                }

                Map<String, Object> featureConfig = (Map<String, Object>) item;
                if (!isBuildingFeatureConfig(featureConfig)) {
                    continue;
                }

                buildingConfigCount++;
                Object pathObj = featureConfig.get("path");
                Path shpPath = pathObj == null ? null : resolveBuildingShpPath(meshDir, String.valueOf(pathObj));
                if (shpPath != null) {
                    shpPaths.add(shpPath);
                } else {
                    unresolvedPathCount++;
                }
            }

            if (buildingConfigCount > 0 && unresolvedPathCount > 0) {
                log.info("Building white model TIN config has unresolved building shp paths: {}/{}",
                        unresolvedPathCount, buildingConfigCount);
                collectLikelyBuildingShps(meshDir, shpPaths, buildingConfigCount == 1);
            }
        } catch (Exception e) {
            log.warn("Failed to parse adaptive TIN config for building white models: {}", e.getMessage());
        }
    }

    private boolean isBuildingFeatureConfig(Map<String, Object> featureConfig) {
        if (featureConfig == null) {
            return false;
        }

        String featureType = String.valueOf(firstNonNull(
                featureConfig.get("featureType"),
                featureConfig.get("feature_type"),
                featureConfig.get("type")));
        if ("building".equalsIgnoreCase(featureType)) {
            return true;
        }

        Object hole = firstNonNull(featureConfig.get("hole"), featureConfig.get("isHole"));
        String mode = String.valueOf(featureConfig.getOrDefault("mode", ""));
        String pathText = String.valueOf(firstNonNull(
                featureConfig.get("path"),
                featureConfig.get("shpPath"),
                featureConfig.get("fileName"),
                featureConfig.get("fileNames")));
        return Boolean.TRUE.equals(hole)
                && ("hole".equalsIgnoreCase(mode) || looksLikeBuildingFileName(pathText));
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Path resolveBuildingShpPath(Path meshDir, String rawPath) throws IOException {
        if (rawPath == null || rawPath.trim().isEmpty() || "null".equalsIgnoreCase(rawPath.trim())) {
            return null;
        }

        Path path = Paths.get(rawPath);
        if (Files.exists(path) && Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp")) {
            return path;
        }

        String fileName = safeFileName(path.getFileName() == null ? rawPath : path.getFileName().toString());
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".shp")) {
            Path shpPath = resolveAdaptiveMeshShp(meshDir, fileName);
            if (shpPath != null) {
                return shpPath;
            }
        }

        return null;
    }

    private void collectLikelyBuildingShps(Path meshDir, Set<Path> shpPaths, boolean allowSingleFallback) throws IOException {
        List<Path> allShps;
        try (java.util.stream.Stream<Path> stream = Files.list(meshDir)) {
            allShps = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp"))
                    .collect(Collectors.toList());
        }

        List<Path> namedBuildingShps = allShps.stream()
                .filter(path -> looksLikeBuildingFileName(path.getFileName().toString()))
                .collect(Collectors.toList());
        if (!namedBuildingShps.isEmpty()) {
            shpPaths.addAll(namedBuildingShps);
            return;
        }

        if (allowSingleFallback && allShps.size() == 1) {
            shpPaths.add(allShps.get(0));
        }
    }

    private boolean looksLikeBuildingFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.contains("building")
                || lower.contains("buildings")
                || lower.contains("jianzhu")
                || fileName.contains("\u5efa\u7b51");
    }

    private Path resolveAdaptiveMeshShp(Path meshDir, String fileName) throws IOException {
        Path exact = meshDir.resolve(fileName);
        if (Files.exists(exact) && Files.isRegularFile(exact)) {
            return exact;
        }

        String targetName = fileName.toLowerCase(Locale.ROOT);
        String targetBase = stripExtension(targetName);
        try (java.util.stream.Stream<Path> stream = Files.list(meshDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp"))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.equals(targetName) || stripExtension(name).equals(targetBase) || stripExtension(name).startsWith(targetBase + "_");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private CoordinateReferenceSystem readShapefileCrs(Path shpPath) {
        if (shpPath == null) {
            return null;
        }
        String fileName = shpPath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        Path prjPath = shpPath.resolveSibling(baseName + ".prj");
        if (!Files.exists(prjPath) || !Files.isRegularFile(prjPath)) {
            return null;
        }

        try {
            String wkt = new String(Files.readAllBytes(prjPath), StandardCharsets.UTF_8).trim();
            if (wkt.isEmpty()) {
                return null;
            }
            return CRS.parseWKT(wkt);
        } catch (Exception e) {
            log.warn("Failed to parse building shapefile projection: {}, {}", prjPath, e.getMessage());
            return null;
        }
    }

    private void appendBuildingFeatures(List<Map<String, Object>> output, Path shpPath) throws Exception {
        int beforeCount = output.size();
        Map<String, Object> params = new HashMap<>();
        params.put("url", shpPath.toUri().toURL());

        DataStore dataStore = DataStoreFinder.getDataStore(params);
        if (dataStore == null) {
            log.warn("无法读取建筑Shapefile: {}", shpPath);
            return;
        }

        try {
            String typeName = dataStore.getTypeNames()[0];
            FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
            SimpleFeatureType schema = source.getSchema();
            CoordinateReferenceSystem sourceCrs = schema.getCoordinateReferenceSystem();
            if (sourceCrs == null) {
                sourceCrs = readShapefileCrs(shpPath);
            }
            CoordinateReferenceSystem targetCrs = CRS.decode("EPSG:4326", true);
            MathTransform sourceTransform = sourceCrs == null ? null : CRS.findMathTransform(sourceCrs, targetCrs, true);
            MathTransform webMercatorTransform = CRS.findMathTransform(CRS.decode("EPSG:3857", true), targetCrs, true);
            FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();

            try (FeatureIterator<SimpleFeature> iterator = collection.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Object geometryObj = feature.getDefaultGeometry();
                    if (!(geometryObj instanceof Geometry)) {
                        continue;
                    }

                    Geometry geometry = (Geometry) geometryObj;
                    geometry = transformBuildingGeometryToLonLat(geometry, sourceTransform, webMercatorTransform);

                    addGeometryAsBuildingFeature(output, geometry, feature, shpPath);
                }
            }
        } finally {
            dataStore.dispose();
        }
        log.info("Loaded building white model shapefile: {}, features={}", shpPath, output.size() - beforeCount);
    }

    private Geometry transformBuildingGeometryToLonLat(Geometry geometry,
                                                       MathTransform sourceTransform,
                                                       MathTransform webMercatorTransform) {
        if (geometry == null || geometry.isEmpty() || geometryHasValidLonLat(geometry)) {
            return geometry;
        }

        if (looksLikeWebMercator(geometry.getEnvelopeInternal())) {
            Geometry webMercatorGeometry = transformGeometry(geometry, webMercatorTransform);
            if (geometryHasValidLonLat(webMercatorGeometry)) {
                return webMercatorGeometry;
            }
        }

        Geometry sourceGeometry = transformGeometry(geometry, sourceTransform);
        if (geometryHasValidLonLat(sourceGeometry)) {
            return sourceGeometry;
        }

        Geometry webMercatorGeometry = transformGeometry(geometry, webMercatorTransform);
        if (geometryHasValidLonLat(webMercatorGeometry)) {
            return webMercatorGeometry;
        }

        return sourceGeometry != null ? sourceGeometry : geometry;
    }

    private Geometry transformGeometry(Geometry geometry, MathTransform transform) {
        if (geometry == null || transform == null) {
            return null;
        }
        try {
            return JTS.transform(geometry, transform);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean geometryHasValidLonLat(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return false;
        }
        Coordinate[] coordinates = geometry.getCoordinates();
        if (coordinates == null || coordinates.length == 0) {
            return false;
        }
        for (Coordinate coordinate : coordinates) {
            if (!isValidLonLat(coordinate.x, coordinate.y)) {
                return false;
            }
        }
        return true;
    }

    private boolean looksLikeWebMercator(Envelope envelope) {
        if (envelope == null || envelope.isNull()) {
            return false;
        }
        double maxAbsX = Math.max(Math.abs(envelope.getMinX()), Math.abs(envelope.getMaxX()));
        double maxAbsY = Math.max(Math.abs(envelope.getMinY()), Math.abs(envelope.getMaxY()));
        double extent = 20037508.342789244;
        return maxAbsX > 1000000.0
                && maxAbsY > 1000000.0
                && maxAbsX <= extent
                && maxAbsY <= extent;
    }

    private void addGeometryAsBuildingFeature(List<Map<String, Object>> output,
                                              Geometry geometry,
                                              SimpleFeature sourceFeature,
                                              Path shpPath) {
        if (geometry == null || geometry.isEmpty()) {
            return;
        }

        if (geometry instanceof Polygon) {
            Map<String, Object> feature = createBuildingFeature((Polygon) geometry, sourceFeature, shpPath, output.size());
            if (feature != null) {
                output.add(feature);
            }
            return;
        }

        if (geometry instanceof MultiPolygon) {
            MultiPolygon multiPolygon = (MultiPolygon) geometry;
            List<List<List<List<Double>>>> coordinates = new ArrayList<>();
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                Geometry part = multiPolygon.getGeometryN(i);
                if (part instanceof Polygon) {
                    List<List<List<Double>>> polygonCoords = polygonToGeoJsonCoordinates((Polygon) part);
                    if (!polygonCoords.isEmpty()) {
                        coordinates.add(polygonCoords);
                    }
                }
            }
            if (!coordinates.isEmpty()) {
                output.add(createBuildingFeature("MultiPolygon", coordinates, sourceFeature, shpPath, output.size()));
            }
            return;
        }

        if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                addGeometryAsBuildingFeature(output, geometry.getGeometryN(i), sourceFeature, shpPath);
            }
        }
    }

    private Map<String, Object> createBuildingFeature(Polygon polygon,
                                                      SimpleFeature sourceFeature,
                                                      Path shpPath,
                                                      int index) {
        List<List<List<Double>>> coordinates = polygonToGeoJsonCoordinates(polygon);
        if (coordinates.isEmpty()) {
            return null;
        }
        return createBuildingFeature("Polygon", coordinates, sourceFeature, shpPath, index);
    }

    private Map<String, Object> createBuildingFeature(String geometryType,
                                                      Object coordinates,
                                                      SimpleFeature sourceFeature,
                                                      Path shpPath,
                                                      int index) {
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("type", geometryType);
        geometry.put("coordinates", coordinates);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source", shpPath.getFileName().toString());
        properties.put("height", resolveBuildingHeight(sourceFeature));
        properties.put("name", resolveBuildingName(sourceFeature, index));

        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("properties", properties);
        feature.put("geometry", geometry);
        return feature;
    }

    private List<List<List<Double>>> polygonToGeoJsonCoordinates(Polygon polygon) {
        List<List<List<Double>>> rings = new ArrayList<>();
        List<List<Double>> outer = lineStringToGeoJsonRing(polygon.getExteriorRing());
        if (outer.size() < 4) {
            return rings;
        }
        rings.add(outer);
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            List<List<Double>> hole = lineStringToGeoJsonRing(polygon.getInteriorRingN(i));
            if (hole.size() >= 4) {
                rings.add(hole);
            }
        }
        return rings;
    }

    private List<List<Double>> lineStringToGeoJsonRing(LineString ring) {
        List<List<Double>> result = new ArrayList<>();
        Coordinate[] coordinates = ring.getCoordinates();
        for (Coordinate coordinate : coordinates) {
            if (!isValidLonLat(coordinate.x, coordinate.y)) {
                continue;
            }
            result.add(Arrays.asList(roundCoordinate(coordinate.x), roundCoordinate(coordinate.y)));
        }
        return result;
    }

    private double resolveBuildingHeight(SimpleFeature feature) {
        Double height = readPositiveDoubleAttribute(feature, "height", "HEIGHT", "Height", "高度", "建筑高度", "BLDG_HGT");
        if (height != null) {
            return height;
        }

        Double floors = readPositiveDoubleAttribute(feature, "floor", "floors", "FLOOR", "FLOORS", "层数", "楼层", "建筑层数");
        if (floors != null) {
            return Math.max(3.0, floors * 3.0);
        }

        return DEFAULT_BUILDING_WHITE_MODEL_HEIGHT;
    }

    private String resolveBuildingName(SimpleFeature feature, int index) {
        Object name = readAttributeIgnoreCase(feature, "name", "NAME", "Name", "建筑名称", "名称");
        if (name == null || String.valueOf(name).trim().isEmpty()) {
            return "building_" + (index + 1);
        }
        return String.valueOf(name).trim();
    }

    private Double readPositiveDoubleAttribute(SimpleFeature feature, String... names) {
        Object value = readAttributeIgnoreCase(feature, names);
        if (value == null) {
            return null;
        }

        try {
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                return number > 0 ? number : null;
            }

            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                return null;
            }
            text = text.replaceAll("[^0-9+\\-.Ee]", "");
            if (text.isEmpty()) {
                return null;
            }
            double number = Double.parseDouble(text);
            return number > 0 ? number : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object readAttributeIgnoreCase(SimpleFeature feature, String... names) {
        if (feature == null || names == null) {
            return null;
        }

        Set<String> wanted = Arrays.stream(names)
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (org.opengis.feature.Property property : feature.getProperties()) {
            String localName = property.getName().getLocalPart();
            if (localName != null && wanted.contains(localName.toLowerCase(Locale.ROOT))) {
                return property.getValue();
            }
        }
        return null;
    }

    private String safeFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String normalized = fileName.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(slashIndex + 1);
        }
        return normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private boolean isValidLonLat(double longitude, double latitude) {
        return Double.isFinite(longitude)
                && Double.isFinite(latitude)
                && longitude >= -180.0
                && longitude <= 180.0
                && latitude >= -90.0
                && latitude <= 90.0;
    }

    private double roundCoordinate(double value) {
        return Math.round(value * 100000000.0) / 100000000.0;
    }
    private Map<String, Object> loadSWWFramesFromDirectory(Path swwFramesDir) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> imageFrames = new ArrayList<>();
        
        Path imgsJsonPath = swwFramesDir.resolve("Imgs.json");
        if (Files.exists(imgsJsonPath)) {
            log.info("找到Imgs.json文件,从JSON加载SWW数据: {}", imgsJsonPath);
            try {
                String jsonContent = new String(Files.readAllBytes(imgsJsonPath), StandardCharsets.UTF_8);
                Map<String, Object> imgsJsonData = JSON_MAPPER.readValue(jsonContent, Map.class);
                
                if (imgsJsonData.containsKey("metadata")) {
                    Map<String, Object> metadata = (Map<String, Object>) imgsJsonData.get("metadata");
                    result.put("metadata", metadata);
                    log.info("加载metadata: total_images={}", metadata.get("total_images"));
                }
                
                if (imgsJsonData.containsKey("images")) {
                    List<Map<String, Object>> images = (List<Map<String, Object>>) imgsJsonData.get("images");
                    
                    for (Map<String, Object> imageEntry : images) {
                        Map<String, Object> frameData = new HashMap<>();
                        frameData.put("frame", imageEntry.get("frame_number"));
                        frameData.put("time", imageEntry.get("time_value"));
                        frameData.put("filePath", swwFramesDir.resolve((String) imageEntry.get("filename")).toString());
                        frameData.put("base64", imageEntry.get("base64_data"));
                        frameData.put("dataUrl", imageEntry.get("data_url"));
                        if (imageEntry.containsKey("stats")) {
                            frameData.put("stats", imageEntry.get("stats"));
                        }
                        
                        imageFrames.add(frameData);
                    }
                    
                    log.info("从Imgs.json加载了{}帧图像数据", imageFrames.size());
                }
                
                result.put("imageFrames", imageFrames);
                result.put("processedFrames", imageFrames.size());
                result.put("imageOutputDir", swwFramesDir.toString());
                result.put("status", "success");
                result.put("message", "SWW历史数据从Imgs.json加载完成");
                result.put("loadSource", "Imgs.json");
                
                return result;
                
            } catch (Exception e) {
                log.warn("读取Imgs.json失败,将fallback到扫描PNG文件: {}", e.getMessage());
            }
        } else {
            log.info("未找到Imgs.json文件,将扫描PNG文件: {}", imgsJsonPath);
        }
        
        List<Path> frameFiles = Files.walk(swwFramesDir, 1)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".png"))
                .sorted()
                .collect(Collectors.toList());
        
        if (frameFiles.isEmpty()) {
            throw new Exception("sww_frames目录中没有图像文件");
        }
        
        log.info("找到{}个SWW图像帧(通过扫描PNG)", frameFiles.size());
        
        for (int i = 0; i < frameFiles.size(); i++) {
            Path frameFile = frameFiles.get(i);
            byte[] imageBytes = Files.readAllBytes(frameFile);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            
            Map<String, Object> frameData = new HashMap<>();
            frameData.put("frame", i);
            frameData.put("filePath", frameFile.toString());
            frameData.put("base64", base64Image);
            frameData.put("dataUrl", "data:image/png;base64," + base64Image);
            
            imageFrames.add(frameData);
        }
        
        result.put("imageFrames", imageFrames);
        result.put("processedFrames", imageFrames.size());
        result.put("imageOutputDir", swwFramesDir.toString());
        result.put("status", "success");
        result.put("message", "SWW历史数据加载完成(从PNG扫描)");
        result.put("loadSource", "PNG files");
        
        return result;
    }
    private Map<String, Object> loadSWMMDataFromDirectory(Path outputDir) throws Exception {
        Map<String, Object> result = new HashMap<>();
        
        List<Path> jsonFiles = Files.walk(outputDir, 1)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith("_time_based.json") || 
                           p.toString().endsWith("_object_based.json"))
                .collect(Collectors.toList());
        
        if (jsonFiles.isEmpty()) {
            throw new Exception("output目录中没有找到SWMM JSON文件");
        }
        
        log.info("找到{}个SWMM JSON文件", jsonFiles.size());
        
        for (Path jsonFile : jsonFiles) {
            String fileName = jsonFile.getFileName().toString();
            String jsonContent = new String(Files.readAllBytes(jsonFile), StandardCharsets.UTF_8);
            Map<String, Object> jsonData = JSON_MAPPER.readValue(jsonContent, Map.class);
            
            if (fileName.contains("time_based")) {
                result.put("timeBasedData", jsonData);
                result.put("timeBasedJsonFile", jsonFile.toString());
                log.info("加载时间序列数据: {} 个时间步", jsonData.size());
            } else if (fileName.contains("object_based")) {
                result.put("objectBasedData", jsonData);
                result.put("objectBasedJsonFile", jsonFile.toString());
                log.info("加载对象数据: {} 个对象类型", jsonData.size());
            }
        }
        
        if (!result.containsKey("timeBasedData") && !result.containsKey("objectBasedData")) {
            throw new Exception("未找到有效的SWMM数据");
        }
        
        result.put("processor", "history-loader");
        result.put("outputDirectory", outputDir.toString());
        result.put("timeBasedDataInline", true);
        result.put("objectBasedDataInline", true);
        
        return result;
    }

    @Override
    public String simulateWithRegion(cn.com.medoxcq.module.warning.module.simulation.pojo.bo.UrbanFloodSimulationBO bo, 
                                    MultipartFile csvFile) throws Exception {
        log.info("开始基于区域进行降雨模拟: regionId={}, simulationName={}", bo.getRegionId(), bo.getSimulationName());

        cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion region = 
            urbanFloodFileManager.getRegionById(bo.getRegionId());
        
        if (region == null) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域不存在");
        }
        
        if (region.getInitStatus() != 2) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST.getCode(), "区域未初始化完成");
        }

        ModelSimulationHistoryQueryBO queryBO = new ModelSimulationHistoryQueryBO();
        queryBO.setModelName(ModelNameEnum.ANUGA_SWMM);
        queryBO.setState(SimulationStatusEnum.RUNNING);
        List<ModelSimulationHistory> histories = historyService.queryRunningModel(queryBO);
        if (CollectionUtil.isNotEmpty(histories)) {
            throw new BusinessException(ErrorCodeEnum.MODEL_RUNNING);
        }

        ModelSimulationHistorySaveBO historySaveBO = new ModelSimulationHistorySaveBO();
        historySaveBO.setState(SimulationStatusEnum.RUNNING.getCode());
        historySaveBO.setSimulationName(bo.getSimulationName());
        historySaveBO.setModelName(ModelNameEnum.ANUGA_SWMM.getCode());
        historySaveBO.setSaveData(bo.getSaveData());
        historySaveBO.setSimulationParams(JSON_MAPPER.writeValueAsString(bo));
        String simulationId = historyService.save(historySaveBO);

        // Object loginId = StpUtil.getLoginId();
        // if (ObjectUtil.isNull(loginId)) {
        // }
        String topic = String.format(TOPIC_TEMPLATE, bo.getSessionId());

        try {
            log.info("创建模拟工作空间...");
            UrbanFloodFileManager.ProjectWorkspace workspace = 
                urbanFloodFileManager.createSimulationWorkspace(bo.getSimulationName(), region, csvFile);
            
            ModelSimulationHistorySaveBO workspaceUpdateBO = new ModelSimulationHistorySaveBO();
            workspaceUpdateBO.setId(simulationId);
            workspaceUpdateBO.setWorkspacePath(workspace.getProjectDir());
            historyService.update(workspaceUpdateBO);
            log.info("工作空间路径已保存: {}", workspace.getProjectDir());

            Map<String, Object> buildingModelData = prepareBuildingWhiteModelData(region, Paths.get(workspace.getProjectDir()));

            log.info("复制管点Shapefile到工作空间...");
            String workspaceNodeShpPath = copyShapefileToWorkspace(region.getNodeShpPath(), 
                workspace.getProjectDir(), "node");
            log.info("管点Shapefile已复制: {}", workspaceNodeShpPath);
            
            log.info("复制管线Shapefile到工作空间...");
            String workspaceLinkShpPath = copyShapefileToWorkspace(region.getLinkShpPath(), 
                workspace.getProjectDir(), "link");
            log.info("管线Shapefile已复制: {}", workspaceLinkShpPath);

            log.info("复制DEM文件到工作空间...");
            String demFileName = Paths.get(region.getDemFilePath()).getFileName().toString();
            String workspaceDemPath = Paths.get(workspace.getProjectDir(), demFileName).toString();
            Files.copy(Paths.get(region.getDemFilePath()), Paths.get(workspaceDemPath),
                StandardCopyOption.REPLACE_EXISTING);
            log.info("DEM文件已复制: {}", workspaceDemPath);

            String demPrjPath = region.getDemFilePath().replace(".asc", ".prj");
            if (Files.exists(Paths.get(demPrjPath))) {
                String workspaceDemPrjPath = workspaceDemPath.replace(".asc", ".prj");
                Files.copy(Paths.get(demPrjPath), Paths.get(workspaceDemPrjPath),
                    StandardCopyOption.REPLACE_EXISTING);
                log.info("DEM投影文件已复制: {}", workspaceDemPrjPath);
            }

            String workspaceLandusePath = null;
            if (region.getLanduseFilePath() != null && !region.getLanduseFilePath().isEmpty()) {
                log.info("复制土地利用文件到工作空间...");
                String landuseFileName = Paths.get(region.getLanduseFilePath()).getFileName().toString();
                workspaceLandusePath = Paths.get(workspace.getProjectDir(), landuseFileName).toString();
                Files.copy(Paths.get(region.getLanduseFilePath()), Paths.get(workspaceLandusePath),
                    StandardCopyOption.REPLACE_EXISTING);
                log.info("土地利用文件已复制: {}", workspaceLandusePath);

                String landusePrjPath = region.getLanduseFilePath().replace(".asc", ".prj");
                if (Files.exists(Paths.get(landusePrjPath))) {
                    String workspaceLandusePrjPath = workspaceLandusePath.replace(".asc", ".prj");
                    Files.copy(Paths.get(landusePrjPath), Paths.get(workspaceLandusePrjPath),
                        StandardCopyOption.REPLACE_EXISTING);
                    log.info("土地利用投影文件已复制: {}", workspaceLandusePrjPath);
                }
            }

            log.info("复制TIN CSV文件...");
            Path tinBasePath = Paths.get(region.getTinFilePath());
            Path tinDir = tinBasePath.getParent();
            if (tinDir == null) {
                throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), "TIN路径无效: " + region.getTinFilePath());
            }

            Path poiSrc = tinDir.resolve("p_a_v_poi.csv");
            Path triSrc = tinDir.resolve("p_a_v_tri.csv");
            Path baseSrc = tinDir.resolve("p_a_v.csv");

            Path poiDst = Paths.get(workspace.getProjectDir(), "p_a_v_poi.csv");
            Path triDst = Paths.get(workspace.getProjectDir(), "p_a_v_tri.csv");
            Path baseDst = Paths.get(workspace.getProjectDir(), "p_a_v.csv");

            Files.copy(poiSrc, poiDst, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(triSrc, triDst, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(baseSrc, baseDst, StandardCopyOption.REPLACE_EXISTING);
            log.info("TIN CSV已复制: poi={}, tri={}", poiDst, triDst);

            log.info("生成INP文件...");

            Map<String, String> nodeFieldMapping = bo.getNodeFieldMapping();
            Map<String, String> linkFieldMapping = bo.getLinkFieldMapping();
            Map<String, String> nodeTypeMapping = bo.getNodeTypeMapping();
            Map<String, String> shapeTypeMapping = bo.getShapeTypeMapping();

            if ((nodeFieldMapping == null || linkFieldMapping == null ||
                 nodeTypeMapping == null || shapeTypeMapping == null) &&
                region.getConfigParams() != null) {
                try {
                    Map<String, Object> configParams = JSON_MAPPER.readValue(region.getConfigParams(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

                    if (nodeFieldMapping == null && configParams.containsKey("nodeFieldMapping")) {
                        nodeFieldMapping = (Map<String, String>) configParams.get("nodeFieldMapping");
                    }
                    if (linkFieldMapping == null && configParams.containsKey("linkFieldMapping")) {
                        linkFieldMapping = (Map<String, String>) configParams.get("linkFieldMapping");
                    }
                    if (nodeTypeMapping == null && configParams.containsKey("nodeTypeMapping")) {
                        nodeTypeMapping = (Map<String, String>) configParams.get("nodeTypeMapping");
                    }
                    if (shapeTypeMapping == null && configParams.containsKey("shapeTypeMapping")) {
                        shapeTypeMapping = (Map<String, String>) configParams.get("shapeTypeMapping");
                    }

                    log.info("已从区域配置恢复字段映射");
                } catch (Exception e) {
                    log.warn("解析区域配置参数失败，将使用默认映射: {}", e.getMessage());
                }
            }

            SwmmInpGenerator generator = new SwmmInpGenerator();
            generator.applyCustomMappings(
                nodeFieldMapping,
                linkFieldMapping,
                nodeTypeMapping,
                shapeTypeMapping);
            List<String> nodeShps = Collections.singletonList(workspaceNodeShpPath);
            List<String> linkShps = Collections.singletonList(workspaceLinkShpPath);
            generator.setSpatialTolerance(0.05);
            generator.setUseDemForDepth(true);
            Integer simDuration = bo.getSimulationDuration() != null ?
                bo.getSimulationDuration().intValue() : null;
            boolean inpGenerated = generator.generateInpFile(nodeShps, linkShps,
                workspace.getInpFilePath(), workspaceDemPath,
                workspace.getCsvFilePath(), simDuration);

            if (!inpGenerated) {
                throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), "INP文件生成失败");
            }
            log.info("INP文件生成成功: {}", workspace.getInpFilePath());

            log.info("生成BCI.CSV...");
            Path bciCsvPath = generateBciCsv(Paths.get(workspace.getInpFilePath()),
                    Paths.get(workspace.getProjectDir(), "bci.csv"));
            log.info("已生成bci.csv: {}", bciCsvPath);

            log.info("生成YAML配置文件...");
            generateConfigYamlFromRegion(region, workspace, bo.getSimulationName(),
                workspaceDemPath, workspaceLandusePath, bo.getSimulationDuration());
            log.info("配置文件已生成");

            log.info("生成模型启动脚本...");
            String generatedYamlPath = Paths.get(workspace.getProjectDir(), "generated_config.yaml").toString();
            modifyBatFile(generatedYamlPath, workspace.getInpFilePath(), workspace);
            log.info("模型启动脚本生成完成");

            log.info("启动ANUGA-SWMM耦合模型计算...");
            String batPath = workspace.getBatFilePath();
            if (batPath == null || batPath.isEmpty()) {
                throw new BusinessException(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), "BAT文件路径为空");
            }

            hasModelOutputMap.put(simulationId, false);
            hasSuccessMarkerMap.put(simulationId, false);
            initializeProgressTracking(simulationId, bo.getSimulationDuration() * 60.0);

            UrbanFloodFileManager.OutputCallback outputCallback = (outputLine) -> {
                try {
                    hasModelOutputMap.put(simulationId, true);
                    if (outputLine.contains("Writing output report") || 
                        outputLine.contains("[SIMULATION_SUCCESS]") ||
                        outputLine.contains("[SUCCESS]") ||
                        outputLine.contains("simulation completed")) {
                        hasSuccessMarkerMap.put(simulationId, true);
                        log.info("检测到模拟成功标记: {}", outputLine);
                    }
                    publishModelTimeProgress(topic, simulationId, outputLine);
                    if (isFinalSuccessMarker(outputLine)) {
                        startResultProcessingOnce(simulationId, workspace, topic, "最终成功标记");
                    }
                    Map<String, Object> message = new HashMap<>();
                    message.put("simulationId", simulationId);
                    message.put("type", "OUTPUT");
                    message.put("content", outputLine);
                    // webSocket.sendMessage(JSON_MAPPER.writeValueAsString(message), loginId);
                    publisher.publishSimulationResult(topic, JSON_MAPPER.writeValueAsString(message));
                } catch (Exception e) {
                    log.error("推送模型输出失败: {}", e.getMessage());
                }
            };

            Process modelProcess = urbanFloodFileManager.executeSimulation(batPath, outputCallback);

            // final Object finalLoginId = loginId;
            CompletableFuture.runAsync(() -> monitorModelExecution(simulationId, modelProcess, workspace, topic));

            Map<String, Object> statusMessage = new HashMap<>();
            statusMessage.put("simulationId", simulationId);
            statusMessage.put("type", "STATUS");
            statusMessage.put("status", "RUNNING");
            statusMessage.put("message", "基于区域" + region.getRegionName() + "的降雨模拟已启动");
            statusMessage.put("stage", "MODEL_STARTING");
            statusMessage.put("progress", MODEL_COMPUTE_PROGRESS_START);
            statusMessage.put("modelTotalSeconds", totalSimulationSecondsMap.get(simulationId));
            if (hasBuildingWhiteModelData(buildingModelData)) {
                statusMessage.put("buildingModelData", buildingModelData);
            }
            // webSocket.sendMessage(JSON_MAPPER.writeValueAsString(statusMessage), loginId);
            publisher.publishSimulationResult(topic, JSON_MAPPER.writeValueAsString(statusMessage));

            return simulationId;

        } catch (Exception e) {
            log.error("降雨模拟执行失败: {}", e.getMessage(), e);
            
            ModelSimulationHistorySaveBO updateBO = new ModelSimulationHistorySaveBO();
            updateBO.setId(simulationId);
            updateBO.setState(SimulationStatusEnum.FAILED.getCode());
            historyService.update(updateBO);
            clearProgressTracking(simulationId);
            
            throw e;
        }
    }
    private void generateConfigYamlFromRegion(cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion region,
                                             UrbanFloodFileManager.ProjectWorkspace workspace,
                                             String simulationName,
                                             String workspaceDemPath,
                                             String workspaceLandusePath,
                                             Double simulationDuration) throws Exception {
        
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> configParams = mapper.readValue(region.getConfigParams(), 
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

        String templatePath = urbanFloodFileManager.getYamlTemplatePath();
        String templateContent = new String(Files.readAllBytes(Paths.get(templatePath)), StandardCharsets.UTF_8);
        
        String projectDir = workspace.getProjectDir();
        String absProjectDir = Paths.get(projectDir).toAbsolutePath().normalize().toString();
        String absCsvPath = workspace.getCsvFilePath() == null ? "" : Paths.get(workspace.getCsvFilePath()).toAbsolutePath().normalize().toString();
        String absDemPath = workspaceDemPath == null ? "" : Paths.get(workspaceDemPath).toAbsolutePath().normalize().toString();
        String absBciPath = Paths.get(absProjectDir, "bci.csv").toString();

        String configContent = templateContent
            .replace("'xxx_p_a_v.xlsx'", "['" + absProjectDir.replace('\\','/') + "/p_a_v.csv','poi','tri']")
            .replace("'outputdir'","'" + absProjectDir.replace('\\','/') + "/'")
            .replace("'xxx_bci.csv'", "'" + absBciPath.replace('\\','/') + "'")
            .replace("'xxx.csv'", "'" + absCsvPath.replace('\\','/') + "'")
            .replace("'xxx.asc'", "'" + absDemPath.replace('\\','/') + "'")
            .replace("'project_name'", "'" + simulationName + "'");
        
        if (configParams.get("manningsN") != null) {
            configContent = configContent.replaceFirst("Mannings_n_parameter: [0-9.]+", 
                "Mannings_n_parameter: " + configParams.get("manningsN"));
        }
        
        if (configParams.get("sedimentConcentration") != null) {
            double value = ((Number) configParams.get("sedimentConcentration")).doubleValue();
            configContent = configContent
                .replaceFirst("inflow_sediment_concentration: [0-9.]+", 
                    "inflow_sediment_concentration: " + value)
                .replaceFirst("initial_sediment_concentration: [0-9.]+", 
                    "initial_sediment_concentration: " + value);
        }
        
        if (configParams.get("vegetationStemDiameter") != null) {
            configContent = configContent.replaceFirst("vegetation_stem_diameter: [0-9.]+", 
                "vegetation_stem_diameter: " + configParams.get("vegetationStemDiameter"));
        }
        
        if (configParams.get("vegetationStemSpacing") != null) {
            configContent = configContent.replaceFirst("vegetation_stem_spacing: [0-9.]+", 
                "vegetation_stem_spacing: " + configParams.get("vegetationStemSpacing"));
        }

        if (simulationDuration != null) {
            configContent = configContent.replaceFirst("simulation_duration: [0-9.]+",
                "simulation_duration: " + simulationDuration);
        }

        Object maxTriangleArea = configParams.get("adaptiveMaxTriangleArea");
        Object tinGridType = configParams.get("tinGridType");
        if ("regular".equalsIgnoreCase(String.valueOf(tinGridType)) && configParams.get("regularGridSize") instanceof Number) {
            double gridSize = ((Number) configParams.get("regularGridSize")).doubleValue();
            maxTriangleArea = gridSize * gridSize;
        }
        if (maxTriangleArea != null) {
            configContent = configContent.replaceFirst("maximum_triangle_area: [0-9.]+",
                    "maximum_triangle_area: " + maxTriangleArea);
        }

        if (workspaceLandusePath != null && !workspaceLandusePath.isEmpty()) {
            String absLanduse = Paths.get(workspaceLandusePath).toAbsolutePath().normalize().toString();
            StringBuilder sb = new StringBuilder();
            sb.append("landuse:\n");
            sb.append("  raster: '").append(absLanduse.replace('\\', '/')).append("'\n");
            sb.append("  class_to_manning:\n");

            @SuppressWarnings("unchecked")
            Map<String, Double> landuseMap = (Map<String, Double>) configParams.get("landuseClassToManning");
            if (landuseMap != null && !landuseMap.isEmpty()) {
                landuseMap.forEach((key, value) -> 
                    sb.append("    ").append(key).append(": ").append(value).append("\n"));
            }

            Object defaultVal = configParams.get("landuseDefaultManning");
            if (defaultVal == null) {
                defaultVal = configParams.get("manningsN");
            }
            sb.append("  default: ").append(defaultVal);

            configContent = upsertYamlBlock(configContent, "landuse", sb.toString());
        }

        String infiltrationBlock = buildInfiltrationBlock(
                configParams.get("infiltrationEnabled"),
                configParams.get("infiltrationStrictClasses"),
                configParams.get("landuseDefaultInfiltration"),
                configParams.get("landuseClassToInfiltration"));
        configContent = upsertYamlBlock(configContent, "infiltration", infiltrationBlock);

        Path configPath = Paths.get(projectDir, "generated_config.yaml");
        Files.write(configPath, Collections.singleton(configContent), StandardCharsets.UTF_8);
        log.info("配置文件已生成: {}", configPath);
    }
    public TinCsvPaths generateTinFromDem(MultipartFile demFile, String outputDir) throws Exception {
        DemGrid grid = parseDemGrid(demFile.getInputStream());

        if (grid.data.length == 0 || grid.data[0].length == 0) {
            throw new IllegalArgumentException("DEM文件无有效数据");
        }

        AdaptiveTINGenerator adaptiveTINGenerator = new AdaptiveTINGenerator();
        AdaptiveTINGenerator.TINMesh mesh = adaptiveTINGenerator.generateAdaptiveTIN(
                grid.data, grid.cellSize, grid.originX, grid.originY);

        log.info("自适应TIN生成完成: 顶点={}，三角形={}",
                mesh.vertices.size(), mesh.triangles.size());

        return writeTinToCsvFiles(mesh, outputDir);
    }

    /**
     *
     */
    public TinCsvPaths generateTinFromDemFile(String demFilePath) throws Exception {
        try (InputStream inputStream = Files.newInputStream(Paths.get(demFilePath))) {
            DemGrid grid = parseDemGrid(inputStream);

            if (grid.data.length == 0 || grid.data[0].length == 0) {
                throw new IllegalArgumentException("DEM文件无有效数据");
            }

            AdaptiveTINGenerator adaptiveTINGenerator = new AdaptiveTINGenerator();
            AdaptiveTINGenerator.TINMesh mesh = adaptiveTINGenerator.generateAdaptiveTIN(
                    grid.data, grid.cellSize, grid.originX, grid.originY);

            log.info("自适应TIN生成完成: 顶点={}，三角形={}",
                    mesh.vertices.size(), mesh.triangles.size());

            Path outputDir = Paths.get(demFilePath).getParent();
            return writeTinToCsvFiles(mesh, outputDir.toString());
        }
    }

    /**
     *
     */
    public TinCsvPaths generateConstrainedAdaptiveTinFromDemFile(String demFilePath,
                                                                 List<AdaptiveTinFeature> features,
                                                                 double maxTriangleArea,
                                                                 double minAngle) throws Exception {
        Path demPath = Paths.get(demFilePath).toAbsolutePath().normalize();
        if (!Files.exists(demPath)) {
            throw new FileNotFoundException("DEM文件不存在: " + demPath);
        }

        Path outputDir = demPath.getParent();
        if (outputDir == null) {
            throw new IllegalArgumentException("DEM文件路径无父目录: " + demPath);
        }

        Path scriptPath = Paths.get(simulationProperties.getUrbanFloodTemplatePath(), "adaptive_tin_generator.py")
                .toAbsolutePath()
                .normalize();
        if (!Files.exists(scriptPath)) {
            throw new FileNotFoundException("未找到自适应TIN生成脚本: " + scriptPath);
        }

        String pythonExecutable = simulationProperties.getPythonExecutable();
        if (pythonExecutable == null || pythonExecutable.trim().isEmpty()) {
            pythonExecutable = "python";
        } else {
            Path pythonPath = Paths.get(pythonExecutable);
            if (!pythonPath.isAbsolute() && Files.exists(pythonPath)) {
                pythonExecutable = pythonPath.toAbsolutePath().normalize().toString();
            }
        }

        List<Map<String, Object>> featureConfigs = new ArrayList<>();
        if (features != null) {
            for (AdaptiveTinFeature feature : features) {
                if (feature == null || feature.getShpPath() == null || feature.getShpPath().trim().isEmpty()) {
                    continue;
                }
                Path shpPath = Paths.get(feature.getShpPath()).toAbsolutePath().normalize();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", shpPath.toString());
                item.put("hole", feature.isHole());
                item.put("feature_type", feature.getFeatureType());
                featureConfigs.add(item);
            }
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dem", demPath.toString());
        config.put("output_dir", outputDir.toString());
        config.put("max_area", maxTriangleArea);
        config.put("min_angle", minAngle);
        config.put("features", featureConfigs);

        Path configPath = outputDir.resolve("adaptive_tin_config.json");
        Files.write(configPath,
                JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(config),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExecutable,
                scriptPath.toString(),
                "--config",
                configPath.toString());
        processBuilder.directory(outputDir.toFile());
        processBuilder.redirectErrorStream(true);

        log.info("调用自适应TIN生成脚本: {} {} --config {}", pythonExecutable, scriptPath, configPath);
        Process process = processBuilder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return readStream(process.getInputStream());
            } catch (IOException e) {
                return "读取自适应TIN脚本输出失败: " + e.getMessage();
            }
        });

        boolean finished = process.waitFor(30, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("自适应TIN生成超时(>30分钟)");
        }

        String scriptOutput = outputFuture.get(5, TimeUnit.SECONDS);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException("自适应TIN生成失败(exit=" + exitCode + "): " + scriptOutput);
        }

        log.info("自适应TIN脚本输出: {}", scriptOutput);

        Path poiPath = outputDir.resolve("p_a_v_poi.csv");
        Path triPath = outputDir.resolve("p_a_v_tri.csv");
        Path basePath = outputDir.resolve("p_a_v.csv");
        if (!Files.exists(poiPath) || !Files.exists(triPath) || !Files.exists(basePath)) {
            throw new FileNotFoundException("自适应TIN输出文件不完整: poi=" + poiPath + ", tri=" + triPath + ", base=" + basePath);
        }

        return new TinCsvPaths(poiPath.toString(), triPath.toString(), basePath.toString());
    }

    /**
     *
     */
    public TinCsvPaths generateRegularGridTinFromDemFile(String demFilePath, double gridSize) throws Exception {
        try (InputStream inputStream = Files.newInputStream(Paths.get(demFilePath))) {
            DemGrid grid = parseDemGrid(inputStream);

            if (grid.data.length == 0 || grid.data[0].length == 0) {
                throw new IllegalArgumentException("DEM文件无有效数据");
            }

            AdaptiveTINGenerator.TINMesh mesh = generateRegularGridTIN(
                    grid.data, grid.cellSize, grid.originX, grid.originY, gridSize);

            log.info("规则网格TIN生成完成: 顶点={}，三角形={}，网格大小={} 米",
                    mesh.vertices.size(), mesh.triangles.size(), gridSize);

            Path outputDir = Paths.get(demFilePath).getParent();
            return writeTinToCsvFiles(mesh, outputDir.toString());
        }
    }

    /**
     * 
     */
    private AdaptiveTINGenerator.TINMesh generateRegularGridTIN(double[][] demData, 
                                                                double cellSize,
                                                                double originX, 
                                                                double originY,
                                                                double gridSize) {
        int nrows = demData.length;
        int ncols = demData[0].length;
        
        double xMin = originX;
        double xMax = originX + ncols * cellSize;
        double yMin = originY;
        double yMax = originY + nrows * cellSize;
        
        int gridCols = (int) Math.ceil((xMax - xMin) / gridSize) + 1;
        int gridRows = (int) Math.ceil((yMax - yMin) / gridSize) + 1;
        
        log.info("生成规则网格: {}x{} 个点，网格间距={} 米", gridRows, gridCols, gridSize);
        
        List<AdaptiveTINGenerator.DEMPoint> vertices = new ArrayList<>();
        Map<String, Integer> coordToIndex = new HashMap<>();
        
        for (int row = 0; row < gridRows; row++) {
            for (int col = 0; col < gridCols; col++) {
                double x = xMin + col * gridSize;
                double y = yMin + row * gridSize;
                
                double elevation = interpolateElevationFromDEM(x, y, demData, originX, originY, cellSize);
                
                if (!Double.isNaN(elevation)) {
                    AdaptiveTINGenerator.DEMPoint vertex = new AdaptiveTINGenerator.DEMPoint(x, y, elevation);
                    int index = vertices.size();
                    vertices.add(vertex);
                    coordToIndex.put(row + "," + col, index);
                }
            }
        }
        
        List<AdaptiveTINGenerator.Triangle> triangles = new ArrayList<>();
        
        for (int row = 0; row < gridRows - 1; row++) {
            for (int col = 0; col < gridCols - 1; col++) {
                Integer i00 = coordToIndex.get(row + "," + col);
                Integer i01 = coordToIndex.get(row + "," + (col + 1));
                Integer i10 = coordToIndex.get((row + 1) + "," + col);
                Integer i11 = coordToIndex.get((row + 1) + "," + (col + 1));
                
                if (i00 != null && i01 != null && i10 != null && i11 != null) {
                    AdaptiveTINGenerator.DEMPoint p00 = vertices.get(i00);
                    AdaptiveTINGenerator.DEMPoint p01 = vertices.get(i01);
                    AdaptiveTINGenerator.DEMPoint p10 = vertices.get(i10);
                    AdaptiveTINGenerator.DEMPoint p11 = vertices.get(i11);
                    
                    double elevationRange = Math.max(Math.max(p00.z, p01.z), Math.max(p10.z, p11.z)) - 
                                           Math.min(Math.min(p00.z, p01.z), Math.min(p10.z, p11.z));
                    
                    triangles.add(new AdaptiveTINGenerator.Triangle(i00, i10, i11));
                    triangles.add(new AdaptiveTINGenerator.Triangle(i00, i11, i01));
                }
            }
        }
        
        log.info("规则网格TIN初步生成: 顶点数={}, 三角形数={}", vertices.size(), triangles.size());
        
        List<AdaptiveTINGenerator.Triangle> validTriangles = new ArrayList<>();
        for (AdaptiveTINGenerator.Triangle tri : triangles) {
            AdaptiveTINGenerator.DEMPoint p0 = vertices.get(tri.p1);
            AdaptiveTINGenerator.DEMPoint p1 = vertices.get(tri.p2);
            AdaptiveTINGenerator.DEMPoint p2 = vertices.get(tri.p3);
            
            double area = Math.abs(
                (p1.x - p0.x) * (p2.y - p0.y) - 
                (p2.x - p0.x) * (p1.y - p0.y)
            ) / 2.0;
            
            double minArea = gridSize * gridSize * 0.01;
            if (area > minArea) {
                validTriangles.add(tri);
            }
        }
        
        if (validTriangles.size() < triangles.size()) {
            log.info("移除了 {} 个退化三角形", triangles.size() - validTriangles.size());
            triangles = validTriangles;
        }
        
        Set<Integer> usedVertices = new HashSet<>();
        for (AdaptiveTINGenerator.Triangle tri : triangles) {
            usedVertices.add(tri.p1);
            usedVertices.add(tri.p2);
            usedVertices.add(tri.p3);
        }
        
        if (usedVertices.size() < vertices.size()) {
            log.info("发现 {} 个未使用的顶点，进行清理", vertices.size() - usedVertices.size());
            
            List<AdaptiveTINGenerator.DEMPoint> newVertices = new ArrayList<>();
            Map<Integer, Integer> oldToNewIndex = new HashMap<>();
            
            for (int oldIndex : usedVertices) {
                int newIndex = newVertices.size();
                newVertices.add(vertices.get(oldIndex));
                oldToNewIndex.put(oldIndex, newIndex);
            }
            
            for (AdaptiveTINGenerator.Triangle tri : triangles) {
                tri.p1 = oldToNewIndex.get(tri.p1);
                tri.p2 = oldToNewIndex.get(tri.p2);
                tri.p3 = oldToNewIndex.get(tri.p3);
            }
            
            vertices = newVertices;
            log.info("清理后: 顶点数={}, 三角形数={}", vertices.size(), triangles.size());
        }
        
        AdaptiveTINGenerator.TINMesh mesh = new AdaptiveTINGenerator.TINMesh();
        mesh.vertices = vertices;
        mesh.triangles = triangles;
        
        return mesh;
    }
    private double interpolateElevationFromDEM(double x, double y, double[][] demData,
                                              double originX, double originY, double cellSize) {
        int nrows = demData.length;
        int ncols = demData[0].length;
        
        double col = (x - originX) / cellSize;
        double row = (demData.length - 1) - (y - originY) / cellSize;
        
        if (col < 0 || col > ncols - 1 || row < 0 || row > nrows - 1) {
            return Double.NaN;
        }
        
        if (col == ncols - 1 || row == nrows - 1) {
            int nearRow = (int) Math.round(row);
            int nearCol = (int) Math.round(col);
            if (nearRow >= nrows) nearRow = nrows - 1;
            if (nearCol >= ncols) nearCol = ncols - 1;
            double z = demData[nearRow][nearCol];
            return (z == -9999) ? Double.NaN : z;
        }
        
        int col0 = (int) Math.floor(col);
        int col1 = col0 + 1;
        int row0 = (int) Math.floor(row);
        int row1 = row0 + 1;
        
        double dx = col - col0;
        double dy = row - row0;
        
        double z00 = demData[row0][col0];
        double z01 = demData[row0][col1];
        double z10 = demData[row1][col0];
        double z11 = demData[row1][col1];
        
        if (z00 == -9999 || z01 == -9999 || z10 == -9999 || z11 == -9999) {
            return Double.NaN;
        }
        
        double z = z00 * (1 - dx) * (1 - dy) +
                   z01 * dx * (1 - dy) +
                   z10 * (1 - dx) * dy +
                   z11 * dx * dy;
        
        return z;
    }
    private String copyShapefileToWorkspace(String sourceShpPath, String workspaceDir, String prefix) throws IOException {
        if (sourceShpPath == null || sourceShpPath.isEmpty()) {
            throw new IllegalArgumentException("源Shapefile路径不能为空");
        }
        
        Path sourcePath = Paths.get(sourceShpPath);
        if (!Files.exists(sourcePath)) {
            throw new FileNotFoundException("Shapefile不存在: " + sourceShpPath);
        }
        
        String fileName = sourcePath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        Path sourceDir = sourcePath.getParent();
        
        Path targetDir = Paths.get(workspaceDir, prefix + "_shp");
        Files.createDirectories(targetDir);
        
        String[] extensions = {".shp", ".dbf", ".shx", ".prj"};
        
        for (String ext : extensions) {
            Path sourceFile = sourceDir.resolve(baseName + ext);
            if (Files.exists(sourceFile)) {
                Path targetFile = targetDir.resolve(baseName + ext);
                Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                log.info("已复制{}文件: {} -> {}", ext, sourceFile, targetFile);
            } else {
                log.warn("{}文件不存在(可选): {}", ext, sourceFile);
            }
        }
        
        return targetDir.resolve(baseName + ".shp").toString();
    }
    private DemGrid parseDemGrid(InputStream inputStream) throws Exception {
        try (Scanner scanner = new Scanner(inputStream)) {
            scanner.useLocale(Locale.US);

            int nCols = parseHeaderInt(scanner, "ncols");
            int nRows = parseHeaderInt(scanner, "nrows");
            double xllCorner = parseHeaderDouble(scanner, "xllcorner");
            double yllCorner = parseHeaderDouble(scanner, "yllcorner");
            double cellSize = parseHeaderDouble(scanner, "cellsize");
            double noDataValue = parseHeaderDouble(scanner, "NODATA_value");

            double[][] data = new double[nRows][nCols];
            int processedRows = 0;
            while (scanner.hasNextLine() && processedRows < nRows) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] values = line.split("\\s+");
                int validCols = Math.min(values.length, nCols);
                for (int c = 0; c < validCols; c++) {
                    try {
                        double z = Double.parseDouble(values[c]);
                        data[processedRows][c] = z == noDataValue ? Double.NaN : z;
                    } catch (NumberFormatException e) {
                        data[processedRows][c] = Double.NaN;
                        log.warn("无法解析DEM值: row={}, col={}, value={}", processedRows, c, values[c]);
                    }
                }
                processedRows++;
            }

            log.info("ASC文件信息: ncols={}, nrows={}, xllcorner={}, yllcorner={}, cellsize={}, nodata={}",
                    nCols, nRows, xllCorner, yllCorner, cellSize, noDataValue);

            DemGrid grid = new DemGrid();
            grid.data = data;
            grid.cellSize = cellSize;
            grid.originX = xllCorner;
            grid.originY = yllCorner;
            grid.noData = noDataValue;
            return grid;
        }
    }
    private List<Coordinate> decimateCoordinates(List<Coordinate> coordinates, int decimationFactor) {
        if (decimationFactor <= 1) {
            return coordinates;
        }
        
        List<Coordinate> decimated = new ArrayList<>();
        for (int i = 0; i < coordinates.size(); i += decimationFactor) {
            decimated.add(coordinates.get(i));
        }
        
        log.info("抽稀前: {} 个点, 抽稀因子: {}, 抽稀后: {} 个点 (保留率: {:.2f}%)", 
                 coordinates.size(), 
                 decimationFactor, 
                 decimated.size(),
                 (decimated.size() * 100.0 / coordinates.size()));
        
        return decimated;
    }

    private int parseHeaderInt(Scanner scanner, String key) throws Exception {
        if (!scanner.next().equalsIgnoreCase(key)) throw new Exception("Invalid DEM header: " + key);
        return scanner.nextInt();
    }

    private double parseHeaderDouble(Scanner scanner, String key) throws Exception {
        if (!scanner.next().equalsIgnoreCase(key)) throw new Exception("Invalid DEM header: " + key);
        return scanner.nextDouble();
    }

    private static class DemGrid {
        double[][] data;
        double cellSize;
        double originX;
        double originY;
        double noData;
    }
    private static class AdaptiveTINGenerator {

        public static class DEMPoint {
            public double x, y, z;
            public double roughness;

            public DEMPoint(double x, double y, double z) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.roughness = 0;
            }
        }

        public static class Triangle {
            public int p1, p2, p3;
            public double centerX, centerY;

            public Triangle(int p1, int p2, int p3) {
                this.p1 = p1;
                this.p2 = p2;
                this.p3 = p3;
            }
        }

        public static class TINMesh {
            public List<DEMPoint> vertices = new ArrayList<>();
            public List<Triangle> triangles = new ArrayList<>();
        }

        private double minRoughness = 0.5;
        private double maxRoughness = 5.0;
        private int minDensity = 10;
        private int maxDensity = 50;

        public TINMesh generateAdaptiveTIN(double[][] demData, double cellSize,
                                           double originX, double originY) {
            int rows = demData.length;
            int cols = demData[0].length;

            double[][] roughnessMap = calculateRoughnessMap(demData);

            List<GridCell> cells = adaptiveSubdivision(roughnessMap, rows, cols);

            List<DEMPoint> sampledPoints = samplePointsFromCells(
                    cells, demData, roughnessMap, cellSize, originX, originY);

            return delaunayTriangulation(sampledPoints);
        }

        private double[][] calculateRoughnessMap(double[][] dem) {
            int rows = dem.length;
            int cols = dem[0].length;
            double[][] roughness = new double[rows][cols];
            int windowSize = 5;

            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    double sum = 0, sumSq = 0;
                    int count = 0;

                    for (int di = -windowSize / 2; di <= windowSize / 2; di++) {
                        for (int dj = -windowSize / 2; dj <= windowSize / 2; dj++) {
                            int ni = i + di;
                            int nj = j + dj;
                            if (ni >= 0 && ni < rows && nj >= 0 && nj < cols) {
                                double val = dem[ni][nj];
                                if (Double.isNaN(val)) {
                                    continue;
                                }
                                sum += val;
                                sumSq += val * val;
                                count++;
                            }
                        }
                    }

                    if (count == 0) {
                        roughness[i][j] = 0;
                        continue;
                    }

                    double mean = sum / count;
                    double variance = (sumSq / count) - (mean * mean);
                    roughness[i][j] = Math.sqrt(Math.max(0, variance));
                }
            }

            return roughness;
        }

        private static class GridCell {
            int rowStart, rowEnd, colStart, colEnd;
            double avgRoughness;

            GridCell(int rs, int re, int cs, int ce, double rough) {
                this.rowStart = rs;
                this.rowEnd = re;
                this.colStart = cs;
                this.colEnd = ce;
                this.avgRoughness = rough;
            }
        }

        private List<GridCell> adaptiveSubdivision(double[][] roughness, int rows, int cols) {
            List<GridCell> cells = new ArrayList<>();
            Queue<GridCell> queue = new LinkedList<>();

            double avgRough = calculateAvgRoughness(roughness, 0, rows, 0, cols);
            queue.offer(new GridCell(0, rows, 0, cols, avgRough));

            while (!queue.isEmpty()) {
                GridCell cell = queue.poll();
                int height = cell.rowEnd - cell.rowStart;
                int width = cell.colEnd - cell.colStart;

                boolean shouldSubdivide = (cell.avgRoughness > minRoughness) &&
                        (height > 4 || width > 4);

                if (shouldSubdivide && height > 2 && width > 2) {
                    int midRow = (cell.rowStart + cell.rowEnd) / 2;
                    int midCol = (cell.colStart + cell.colEnd) / 2;

                    GridCell[] subCells = {
                            new GridCell(cell.rowStart, midRow, cell.colStart, midCol, 0),
                            new GridCell(cell.rowStart, midRow, midCol, cell.colEnd, 0),
                            new GridCell(midRow, cell.rowEnd, cell.colStart, midCol, 0),
                            new GridCell(midRow, cell.rowEnd, midCol, cell.colEnd, 0)
                    };

                    for (GridCell sub : subCells) {
                        sub.avgRoughness = calculateAvgRoughness(
                                roughness, sub.rowStart, sub.rowEnd, sub.colStart, sub.colEnd);
                        queue.offer(sub);
                    }
                } else {
                    cells.add(cell);
                }
            }

            return cells;
        }

        private double calculateAvgRoughness(double[][] roughness,
                                             int rs, int re, int cs, int ce) {
            double sum = 0;
            int count = 0;
            for (int i = rs; i < re; i++) {
                for (int j = cs; j < ce; j++) {
                    sum += roughness[i][j];
                    count++;
                }
            }
            return count > 0 ? sum / count : 0;
        }

        private List<DEMPoint> samplePointsFromCells(List<GridCell> cells,
                                                     double[][] dem,
                                                     double[][] roughness,
                                                     double cellSize,
                                                     double originX,
                                                     double originY) {
            List<DEMPoint> points = new ArrayList<>();

            for (GridCell cell : cells) {
                double normalizedRough = Math.min(cell.avgRoughness / maxRoughness, 1.0);
                int density = (int) (minDensity + normalizedRough * (maxDensity - minDensity));

                int height = cell.rowEnd - cell.rowStart;
                int width = cell.colEnd - cell.colStart;
                int sampleRows = Math.min(density, height);
                int sampleCols = Math.min(density, width);

                for (int i = 0; i < sampleRows; i++) {
                    for (int j = 0; j < sampleCols; j++) {
                        int row = cell.rowStart + (i * height) / sampleRows;
                        int col = cell.colStart + (j * width) / sampleCols;

                        row = Math.min(row, dem.length - 1);
                        col = Math.min(col, dem[0].length - 1);

                        double z = dem[row][col];
                        if (Double.isNaN(z)) {
                            continue;
                        }

                        double x = originX + col * cellSize;
                        double y = originY + row * cellSize;

                        DEMPoint pt = new DEMPoint(x, y, z);
                        pt.roughness = roughness[row][col];
                        points.add(pt);
                    }
                }
            }

            return points;
        }

        private TINMesh delaunayTriangulation(List<DEMPoint> points) {
            TINMesh mesh = new TINMesh();
            mesh.vertices = new ArrayList<>(points);

            if (points.size() < 3) {
                return mesh;
            }

            GeometryFactory factory = new GeometryFactory();
            Map<String, Integer> coordIndex = new HashMap<>();
            List<Coordinate> coordList = new ArrayList<>();

            for (int i = 0; i < points.size(); i++) {
                DEMPoint p = points.get(i);
                String key = p.x + "," + p.y;
                if (!coordIndex.containsKey(key)) {
                    coordIndex.put(key, i);
                    coordList.add(new Coordinate(p.x, p.y));
                }
            }

            DelaunayTriangulationBuilder builder = new DelaunayTriangulationBuilder();
            builder.setSites(coordList);
            QuadEdgeSubdivision subdivision = builder.getSubdivision();
            Geometry trianglesGeom = subdivision.getTriangles(factory);

            List<Triangle> triangles = new ArrayList<>();
            if (trianglesGeom instanceof GeometryCollection) {
                GeometryCollection gc = (GeometryCollection) trianglesGeom;
                for (int gi = 0; gi < gc.getNumGeometries(); gi++) {
                    Geometry g = gc.getGeometryN(gi);
                    Coordinate[] cs = g.getCoordinates();
                    if (cs == null || cs.length < 3) {
                        continue;
                    }
                    Integer i0 = coordIndex.get(cs[0].x + "," + cs[0].y);
                    Integer i1 = coordIndex.get(cs[1].x + "," + cs[1].y);
                    Integer i2 = coordIndex.get(cs[2].x + "," + cs[2].y);
                    if (i0 == null || i1 == null || i2 == null) {
                        continue;
                    }
                    triangles.add(new Triangle(i0, i1, i2));
                }
            }

            mesh.triangles = triangles;
            return mesh;
        }

        private static class Edge {
            int p1, p2;

            Edge(int p1, int p2) {
                this.p1 = Math.min(p1, p2);
                this.p2 = Math.max(p1, p2);
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Edge)) return false;
                Edge e = (Edge) o;
                return p1 == e.p1 && p2 == e.p2;
            }

            @Override
            public int hashCode() {
                return Objects.hash(p1, p2);
            }
        }

        private void addEdgeIfUnique(List<Edge> edges, int p1, int p2) {
            Edge edge = new Edge(p1, p2);
            if (edges.contains(edge)) {
                edges.remove(edge);
            } else {
                edges.add(edge);
            }
        }

        private boolean inCircumcircle(DEMPoint p, Triangle tri, List<DEMPoint> vertices) {
            DEMPoint a = vertices.get(tri.p1);
            DEMPoint b = vertices.get(tri.p2);
            DEMPoint c = vertices.get(tri.p3);

            double ax = a.x - p.x;
            double ay = a.y - p.y;
            double bx = b.x - p.x;
            double by = b.y - p.y;
            double cx = c.x - p.x;
            double cy = c.y - p.y;

            double det = (ax * ax + ay * ay) * (bx * cy - cx * by) -
                    (bx * bx + by * by) * (ax * cy - cx * ay) +
                    (cx * cx + cy * cy) * (ax * by - bx * ay);

            return det > 0;
        }

        public void setDensityParams(int minDensity, int maxDensity) {
            this.minDensity = minDensity;
            this.maxDensity = maxDensity;
        }

        public void setRoughnessThresholds(double minRoughness, double maxRoughness) {
            this.minRoughness = minRoughness;
            this.maxRoughness = maxRoughness;
        }
    }
    @Getter
    @AllArgsConstructor
    public static class AdaptiveTinFeature {
        private final String shpPath;
        private final boolean hole;
        private final String featureType;
    }
    @Getter
    @AllArgsConstructor
    public static class TinCsvPaths {
        private final String poiCsvPath;
        private final String triCsvPath;
        private final String baseCsvPath;
    }
    private TinCsvPaths writeTinToCsvFiles(AdaptiveTINGenerator.TINMesh mesh, String outputDir) throws Exception {
        if (mesh == null || mesh.vertices == null || mesh.triangles == null) {
            throw new IllegalArgumentException("TIN网格数据为空");
        }
        if (mesh.vertices.isEmpty()) {
            throw new IllegalArgumentException("TIN网格没有顶点");
        }
        if (mesh.triangles.isEmpty()) {
            throw new IllegalArgumentException("TIN网格没有三角形");
        }

        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        Path poiPath = dir.resolve("p_a_v_poi.csv");
        Path triPath = dir.resolve("p_a_v_tri.csv");
        Path basePath = dir.resolve("p_a_v.csv");

        try (BufferedWriter writer = Files.newBufferedWriter(poiPath, StandardCharsets.UTF_8)) {
            writer.write("id,x,y");
            writer.newLine();
            for (int i = 0; i < mesh.vertices.size(); i++) {
                AdaptiveTINGenerator.DEMPoint coord = mesh.vertices.get(i);
                if (coord == null || Double.isNaN(coord.x) || Double.isNaN(coord.y)) {
                    log.warn("跳过无效顶点 {}: {}", i, coord);
                    continue;
                }
                writer.write(i + "," + coord.x + "," + coord.y);
                writer.newLine();
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(triPath, StandardCharsets.UTF_8)) {
            writer.write("tinid,p0,p1,p2,p0x,p0y,p1x,p1y,p2x,p2y");
            writer.newLine();

            int tinId = 1;
            int maxIndex = mesh.vertices.size() - 1;
            for (AdaptiveTINGenerator.Triangle tri : mesh.triangles) {
                if (tri.p1 < 0 || tri.p1 > maxIndex || tri.p2 < 0 || tri.p2 > maxIndex || tri.p3 < 0 || tri.p3 > maxIndex) {
                    log.warn("跳过索引越界三角形 tinId={} p1={} p2={} p3={} maxIndex={}", tinId, tri.p1, tri.p2, tri.p3, maxIndex);
                    tinId++;
                    continue;
                }
                AdaptiveTINGenerator.DEMPoint p0 = mesh.vertices.get(tri.p1);
                AdaptiveTINGenerator.DEMPoint p1 = mesh.vertices.get(tri.p2);
                AdaptiveTINGenerator.DEMPoint p2 = mesh.vertices.get(tri.p3);

                if (p0 == null || p1 == null || p2 == null ||
                    Double.isNaN(p0.x) || Double.isNaN(p0.y) ||
                    Double.isNaN(p1.x) || Double.isNaN(p1.y) ||
                    Double.isNaN(p2.x) || Double.isNaN(p2.y)) {
                    log.warn("跳过包含无效顶点的三角形 tinId={} p1={} p2={} p3={}", tinId, tri.p1, tri.p2, tri.p3);
                    tinId++;
                    continue;
                }

                writer.write(tinId + "," + tri.p1 + "," + tri.p2 + "," + tri.p3 + "," +
                        p0.x + "," + p0.y + "," + p1.x + "," + p1.y + "," + p2.x + "," + p2.y);
                writer.newLine();
                tinId++;
            }
        }

        if (!Files.exists(basePath)) {
            Files.write(basePath, Collections.singletonList("poi,tri"), StandardCharsets.UTF_8);
        }

        log.info("TIN已写出CSV: poi={}, tri={}", poiPath, triPath);
        return new TinCsvPaths(poiPath.toString(), triPath.toString(), basePath.toString());
    }
    private byte[] writeTinToExcelBytes(AdaptiveTINGenerator.TINMesh mesh) throws Exception {
        if (mesh == null || mesh.vertices == null || mesh.triangles == null) {
            throw new IllegalArgumentException("TIN网格数据为空");
        }
        
        if (mesh.vertices.isEmpty()) {
            throw new IllegalArgumentException("TIN网格没有顶点");
        }
        
        if (mesh.triangles.isEmpty()) {
            throw new IllegalArgumentException("TIN网格没有三角形");
        }
        
        int maxIndex = mesh.vertices.size() - 1;
        for (int i = 0; i < mesh.triangles.size(); i++) {
            AdaptiveTINGenerator.Triangle tri = mesh.triangles.get(i);
            if (tri.p1 < 0 || tri.p1 > maxIndex || 
                tri.p2 < 0 || tri.p2 > maxIndex || 
                tri.p3 < 0 || tri.p3 > maxIndex) {
                throw new IllegalArgumentException(
                    String.format("三角形 %d 的顶点索引越界: [%d, %d, %d], 有效范围: [0, %d]", 
                        i, tri.p1, tri.p2, tri.p3, maxIndex));
            }
        }
        
        SXSSFWorkbook workbook = new SXSSFWorkbook(1000);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            CellStyle defaultStyle = workbook.createCellStyle();
            Font defaultFont = workbook.createFont();
            defaultFont.setFontName("Arial");
            defaultFont.setFontHeightInPoints((short) 10);
            defaultStyle.setFont(defaultFont);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setFontName("Arial");
            headerFont.setFontHeightInPoints((short) 10);
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            log.info("TIN写入Excel: 顶点={}，三角形={}",
                    mesh.vertices.size(), mesh.triangles.size());

            Sheet poiSheet = workbook.createSheet("poi");
            
            Row poiHeaderRow = poiSheet.createRow(0);
            String[] poiColumns = {"id", "x", "y"};
            for (int i = 0; i < poiColumns.length; i++) {
                Cell cell = poiHeaderRow.createCell(i);
                cell.setCellValue(poiColumns[i]);
                cell.setCellStyle(headerStyle);
            }

            int poiRowIndex = 1;
            for (int i = 0; i < mesh.vertices.size(); i++) {
                AdaptiveTINGenerator.DEMPoint coord = mesh.vertices.get(i);
                
                if (Double.isNaN(coord.x) || Double.isNaN(coord.y) || Double.isNaN(coord.z)) {
                    log.warn("跳过无效顶点 {}: x={}, y={}, z={}", i, coord.x, coord.y, coord.z);
                    continue;
                }
                
                if (Double.isInfinite(coord.x) || Double.isInfinite(coord.y) || Double.isInfinite(coord.z)) {
                    log.warn("跳过无穷大顶点 {}: x={}, y={}, z={}", i, coord.x, coord.y, coord.z);
                    continue;
                }
                
                Row poiRow = poiSheet.createRow(poiRowIndex++);
                
                Cell idCell = poiRow.createCell(0);
                idCell.setCellValue(i);
                idCell.setCellStyle(defaultStyle);
                
                Cell xCell = poiRow.createCell(1);
                xCell.setCellValue(coord.x);
                xCell.setCellStyle(defaultStyle);
                
                Cell yCell = poiRow.createCell(2);
                yCell.setCellValue(coord.y);
                yCell.setCellStyle(defaultStyle);
            }

            log.info("写入了 {} 个节点到poi工作表", mesh.vertices.size());

            poiSheet.setColumnWidth(0, 256 * 8);
            poiSheet.setColumnWidth(1, 256 * 16);
            poiSheet.setColumnWidth(2, 256 * 16);

            Sheet triSheet = workbook.createSheet("tri");

            Row triHeaderRow = triSheet.createRow(0);
            String[] triColumns = {"tinid", "p0", "p1", "p2", "p0x", "p0y", "p1x", "p1y", "p2x", "p2y"};
            for (int i = 0; i < triColumns.length; i++) {
                Cell cell = triHeaderRow.createCell(i);
                cell.setCellValue(triColumns[i]);
                cell.setCellStyle(headerStyle);
            }

            int triRowIndex = 1;
            int tinId = 1;
            int skippedTriangles = 0;

            for (AdaptiveTINGenerator.Triangle tri : mesh.triangles) {
                try {
                    AdaptiveTINGenerator.DEMPoint p0 = mesh.vertices.get(tri.p1);
                    AdaptiveTINGenerator.DEMPoint p1 = mesh.vertices.get(tri.p2);
                    AdaptiveTINGenerator.DEMPoint p2 = mesh.vertices.get(tri.p3);
                    
                    if (Double.isNaN(p0.x) || Double.isNaN(p0.y) || 
                        Double.isNaN(p1.x) || Double.isNaN(p1.y) || 
                        Double.isNaN(p2.x) || Double.isNaN(p2.y)) {
                        log.warn("跳过包含NaN坐标的三角形 {}: p1={}, p2={}, p3={}", 
                            tinId, tri.p1, tri.p2, tri.p3);
                        skippedTriangles++;
                        continue;
                    }

                    Row row = triSheet.createRow(triRowIndex++);
                } catch (IndexOutOfBoundsException e) {
                    log.error("三角形 {} 的顶点索引越界: p1={}, p2={}, p3={}, vertices.size={}", 
                        tinId, tri.p1, tri.p2, tri.p3, mesh.vertices.size());
                    skippedTriangles++;
                    continue;
                }
                
                AdaptiveTINGenerator.DEMPoint p0 = mesh.vertices.get(tri.p1);
                AdaptiveTINGenerator.DEMPoint p1 = mesh.vertices.get(tri.p2);
                AdaptiveTINGenerator.DEMPoint p2 = mesh.vertices.get(tri.p3);
                
                Row row = triSheet.getRow(triRowIndex - 1);

                Cell cell0 = row.createCell(0);
                cell0.setCellValue(tinId);
                cell0.setCellStyle(defaultStyle);

                Cell cell1 = row.createCell(1);
                cell1.setCellValue(tri.p1);
                cell1.setCellStyle(defaultStyle);

                Cell cell2 = row.createCell(2);
                cell2.setCellValue(tri.p2);
                cell2.setCellStyle(defaultStyle);

                Cell cell3 = row.createCell(3);
                cell3.setCellValue(tri.p3);
                cell3.setCellStyle(defaultStyle);

                Cell cell4 = row.createCell(4);
                cell4.setCellValue(p0.x);
                cell4.setCellStyle(defaultStyle);

                Cell cell5 = row.createCell(5);
                cell5.setCellValue(p0.y);
                cell5.setCellStyle(defaultStyle);

                Cell cell6 = row.createCell(6);
                cell6.setCellValue(p1.x);
                cell6.setCellStyle(defaultStyle);

                Cell cell7 = row.createCell(7);
                cell7.setCellValue(p1.y);
                cell7.setCellStyle(defaultStyle);

                Cell cell8 = row.createCell(8);
                cell8.setCellValue(p2.x);
                cell8.setCellStyle(defaultStyle);

                Cell cell9 = row.createCell(9);
                cell9.setCellValue(p2.y);
                cell9.setCellStyle(defaultStyle);

                tinId++;
            }

            if (skippedTriangles > 0) {
                log.warn("跳过了 {} 个无效三角形", skippedTriangles);
            }
            
            log.info("写入了 {} 个三角形到tri工作表", triRowIndex - 1);

            for (int i = 0; i < triColumns.length; i++) {
                triSheet.setColumnWidth(i, 256 * 16);
            }

            workbook.write(baos);
            workbook.dispose();
            return baos.toByteArray();
        } finally {
            workbook.close();
        }
    }
    private Integer findClosestCoordinateIndex(Coordinate target, List<Coordinate> coordinates) {
        double minDistance = Double.MAX_VALUE;
        int closestIndex = -1;
        
        for (int i = 0; i < coordinates.size(); i++) {
            Coordinate coord = coordinates.get(i);
            double distance = Math.sqrt(Math.pow(target.x - coord.x, 2) + Math.pow(target.y - coord.y, 2));
            
            if (distance < minDistance) {
                minDistance = distance;
                closestIndex = i;
            }
        }
        
        return closestIndex >= 0 ? closestIndex : null;
    }
    public Path generateBciCsv(Path inpFilePath, Path outputCsv) throws IOException {
        if (!Files.exists(inpFilePath)) {
            throw new FileNotFoundException("INP文件不存在: " + inpFilePath);
        }
        Files.createDirectories(outputCsv.getParent());

        Set<String> inflowNodes = new HashSet<>();
        boolean inInflows = false;
        boolean inCoordinates = false;
        int count = 0;
        DecimalFormat df = new DecimalFormat("0.############");

        // First pass: collect node names from [INFLOWS]
        try (BufferedReader reader = Files.newBufferedReader(inpFilePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith(";")) {
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    if (trimmed.equalsIgnoreCase("[INFLOWS]")) {
                        inInflows = true;
                        continue;
                    } else if (inInflows) {
                        inInflows = false;
                        continue;
                    }
                }
                if (inInflows) {
                    String dataPart = trimmed.split(";", 2)[0].trim();
                    if (dataPart.isEmpty()) {
                        continue;
                    }
                    String[] parts = dataPart.split("\\s+");
                    if (parts.length > 0) {
                        inflowNodes.add(parts[0]); // First column is node name
                    }
                }
            }
        }

        // Second pass: process [COORDINATES] and output matching nodes
        try (BufferedReader reader = Files.newBufferedReader(inpFilePath, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    if (trimmed.equalsIgnoreCase("[COORDINATES]")) {
                        inCoordinates = true;
                        continue;
                    } else if (inCoordinates) {
                        break;
                    }
                }
                if (!inCoordinates) {
                    continue;
                }
                if (trimmed.startsWith(";")) {
                    continue;
                }
                String dataPart = trimmed.split(";", 2)[0].trim();
                if (dataPart.isEmpty()) {
                    continue;
                }
                String[] parts = dataPart.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }
                String nodeId = parts[0];
                // Only process nodes that appear in [INFLOWS]
                if (!inflowNodes.contains(nodeId)) {
                    continue;
                }
                String xRaw = parts[1];
                String yRaw = parts[2];

                String xOut = xRaw;
                String yOut = yRaw;
                try {
                    double xv = Double.parseDouble(xRaw);
                    double yv = Double.parseDouble(yRaw);
                    xOut = df.format(xv);
                    yOut = df.format(yv);
                } catch (NumberFormatException ignore) {
                }
                writer.write(xOut + "," + yOut + "," + nodeId);
                writer.newLine();
                count++;
            }
        }
        log.debug("Matched {} coordinate records to inflow nodes", count);
        return outputCsv;
    }
    private void generateConfigYaml(MultipartFile demFile, MultipartFile rainfallFile,
                                   MultipartFile landuseFile,
                                   UrbanFloodFileManager.ProjectWorkspace workspace, UrbanFloodSaveBO bo) throws Exception {
        
        String templatePath = urbanFloodFileManager.getYamlTemplatePath();
        String templateContent = new String(Files.readAllBytes(Paths.get(templatePath)), StandardCharsets.UTF_8);
        
        String projectDir = workspace.getProjectDir();
        String absProjectDir = Paths.get(projectDir).toAbsolutePath().normalize().toString();
        String absCsvPath = workspace.getCsvFilePath() == null ? "" : Paths.get(workspace.getCsvFilePath()).toAbsolutePath().normalize().toString();
        String absAscPath = workspace.getAscFilePath() == null ? "" : Paths.get(workspace.getAscFilePath()).toAbsolutePath().normalize().toString();
        String absBciPath = Paths.get(absProjectDir, "bci.csv").toString();
        String absPavPath = Paths.get(absProjectDir, "p_a_v.csv").toString();
        
        String configContent = templateContent
            .replace("'xxx_p_a_v.xlsx'", "['" + absPavPath.replace('\\','/') + "','poi','tri']")
            .replace("'outputdir'","'" + absProjectDir.replace('\\','/') + "/'")
            .replace("'xxx_bci.csv'", "'" + absBciPath.replace('\\','/') + "'");
        
        if (rainfallFile != null && !rainfallFile.isEmpty()) {
            configContent = configContent.replace("'xxx.csv'", "'" + absCsvPath.replace('\\','/') + "'");
        } else {
            configContent = configContent.replace("'xxx.csv'", "''");
        }
        
        if (demFile != null && !demFile.isEmpty()) {
            configContent = configContent.replace("'xxx.asc'", "'" + absAscPath.replace('\\','/') + "'");
        } else {
            configContent = configContent.replace("'xxx.asc'", "''");
        }
        
        if (bo.getProjectName() != null && !bo.getProjectName().trim().isEmpty()) {
            configContent = configContent.replace("'project_name'" , "'" +  bo.getProjectName() + "'");
        }

        if (bo.getManningsN() != null) {
            configContent = configContent.replaceFirst("Mannings_n_parameter: [0-9.]+", 
                    "Mannings_n_parameter: " + bo.getManningsN());
        }
        
        if (bo.getSedimentConcentration() != null) {
            configContent = configContent
                    .replaceFirst("inflow_sediment_concentration: [0-9.]+", 
                            "inflow_sediment_concentration: " + bo.getSedimentConcentration())
                    .replaceFirst("initial_sediment_concentration: [0-9.]+", 
                            "initial_sediment_concentration: " + bo.getSedimentConcentration());
        }
        
        if (bo.getVegetationStemDiameter() != null) {
            configContent = configContent.replaceFirst("vegetation_stem_diameter: [0-9.]+", 
                    "vegetation_stem_diameter: " + bo.getVegetationStemDiameter());
        }
        
        if (bo.getVegetationStemSpacing() != null) {
            configContent = configContent.replaceFirst("vegetation_stem_spacing: [0-9.]+", 
                    "vegetation_stem_spacing: " + bo.getVegetationStemSpacing());
        }

        if (bo.getSimulationDuration() != null) {
            configContent = configContent.replaceFirst("simulation_duration: [0-9.]+",
                    "simulation_duration: " + bo.getSimulationDuration());
        }

        String landuseBlock = buildLanduseBlock(workspace, bo, landuseFile);
        if (!landuseBlock.isEmpty()) {
            if (configContent.contains("landuse:")) {
                configContent = configContent.replaceFirst("(?s)landuse:\\s*.*?(?=\\n\\S|\\z)", java.util.regex.Matcher.quoteReplacement(landuseBlock.trim()));
            } else {
                configContent = configContent + "\n\n" + landuseBlock;
            }
        }

        
        String infiltrationBlock = buildInfiltrationBlock(
                bo.getInfiltrationEnabled(),
                bo.getInfiltrationStrictClasses(),
                bo.getLanduseDefaultInfiltration(),
                bo.getLanduseClassToInfiltration());
        configContent = upsertYamlBlock(configContent, "infiltration", infiltrationBlock);

        Path configPath = Paths.get(absProjectDir, "generated_config.yaml");
        Files.write(configPath, Collections.singleton(configContent), StandardCharsets.UTF_8);
        log.info("配置文件已生成: {}", configPath);
    }
    private String buildLanduseBlock(UrbanFloodFileManager.ProjectWorkspace workspace,
                                     UrbanFloodSaveBO bo,
                                     MultipartFile landuseFile) {
        if (workspace == null) {
            return "";
        }
        String landusePath = workspace.getLanduseFilePath();
        if (landuseFile == null || landuseFile.isEmpty() || landusePath == null || landusePath.isEmpty()) {
            return "";
        }

        String absLandusePath = Paths.get(landusePath).toAbsolutePath().normalize().toString();

        Map<Integer, Double> map = bo.getLanduseClassToManning();
        Double defaultVal = bo.getLanduseDefaultManning() != null
                ? bo.getLanduseDefaultManning()
                : bo.getManningsN();

        StringBuilder sb = new StringBuilder();
        sb.append("landuse:\n");
        sb.append("  raster: '").append(absLandusePath.replace('\\', '/')).append("'\n");
        sb.append("  class_to_manning:\n");

        if (map != null && !map.isEmpty()) {
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sb.append("    ")
                            .append(entry.getKey())
                            .append(": ")
                            .append(entry.getValue())
                            .append("\n"));
        }

        sb.append("  default: ").append(defaultVal == null ? "" : defaultVal);
        return sb.toString();
    }
    private String upsertYamlBlock(String configContent, String blockName, String blockContent) {
        if (blockContent == null || blockContent.trim().isEmpty()) {
            return configContent;
        }
        String block = blockContent.trim();
        if (configContent.contains(blockName + ":")) {
            String regex = "(?s)" + blockName + ":\\s*.*?(?=\\n\\S|\\z)";
            return configContent.replaceFirst(regex, java.util.regex.Matcher.quoteReplacement(block));
        }
        return configContent + "\n\n" + block;
    }

    private String buildInfiltrationBlock(Object enabledObj,
                                          Object strictClassesObj,
                                          Object defaultParamObj,
                                          Object classParamObj) {
        boolean enabled = toBoolean(enabledObj, true);
        boolean strictClasses = toBoolean(strictClassesObj, true);
        Map<String, Object> defaultParams = toStringKeyMap(defaultParamObj);
        Map<?, ?> classParams = classParamObj instanceof Map ? (Map<?, ?>) classParamObj : Collections.emptyMap();

        StringBuilder sb = new StringBuilder();
        sb.append("infiltration:\n");
        sb.append("  enabled: ").append(enabled).append("\n");
        sb.append("  method: horton\n");
        sb.append("  strict_classes: ").append(strictClasses).append("\n");
        sb.append("  units:\n");
        sb.append("    rate: mm/hr\n");
        sb.append("    decay: 1/hr\n");
        sb.append("    depth: mm\n");
        sb.append("  default:\n");
        appendInfiltrationParams(sb, "    ", defaultParams);

        if (classParams.isEmpty()) {
            sb.append("  classes: {}");
            return sb.toString();
        }

        sb.append("  classes:\n");
        List<Map.Entry<?, ?>> entries = new ArrayList<>(classParams.entrySet());
        entries.sort((a, b) -> compareClassKey(a.getKey(), b.getKey()));
        for (Map.Entry<?, ?> entry : entries) {
            if (entry.getKey() == null) {
                continue;
            }
            sb.append("    ").append(entry.getKey()).append(":\n");
            appendInfiltrationParams(sb, "      ", toStringKeyMap(entry.getValue()));
        }
        return sb.toString();
    }

    private void appendInfiltrationParams(StringBuilder sb, String indent, Map<String, Object> params) {
        sb.append(indent).append("max_rate: ")
                .append(getDoubleParam(params, "maxRate", "max_rate", 0.0)).append("\n");
        sb.append(indent).append("min_rate: ")
                .append(getDoubleParam(params, "minRate", "min_rate", 0.0)).append("\n");
        sb.append(indent).append("decay: ")
                .append(getDoubleParam(params, "decay", "decay", 0.0)).append("\n");
        sb.append(indent).append("drying_time_days: ")
                .append(getDoubleParam(params, "dryingTimeDays", "drying_time_days", 7.0)).append("\n");
        sb.append(indent).append("max_infiltration: ")
                .append(getDoubleParam(params, "maxInfiltration", "max_infiltration", 0.0)).append("\n");
    }

    private Map<String, Object> toStringKeyMap(Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }

        Map<?, ?> source;
        if (value instanceof Map) {
            source = (Map<?, ?>) value;
        } else {
            source = JSON_MAPPER.convertValue(value, Map.class);
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private double getDoubleParam(Map<String, Object> params, String camelKey, String snakeKey, double defaultValue) {
        Object value = params.get(camelKey);
        if (value == null) {
            value = params.get(snakeKey);
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int compareClassKey(Object left, Object right) {
        Integer leftNum = toIntegerOrNull(left);
        Integer rightNum = toIntegerOrNull(right);
        if (leftNum != null && rightNum != null) {
            return leftNum.compareTo(rightNum);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private Integer toIntegerOrNull(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void modifyBatFile(String yamlPath, String inpPath, UrbanFloodFileManager.ProjectWorkspace workspace) throws IOException {
        String yamlAbs = Paths.get(yamlPath).toAbsolutePath().normalize().toString();
        String inpAbs = Paths.get(inpPath).toAbsolutePath().normalize().toString();
        String modelRootAbs = Paths.get(simulationProperties.getUrbanFloodModel())
                .toAbsolutePath().normalize().toString();

        String batTemplatePath = urbanFloodFileManager.getBatTemplatePath();
        
        if (batTemplatePath == null || batTemplatePath.isEmpty()) {
            throw new IOException("未配置BAT模板文件路径");
        }
        
        File templateFile = new File(batTemplatePath);

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(templateFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("set MODEL_DIR=")) {
                    line = "set MODEL_DIR=" + modelRootAbs;
                } else if (line.trim().startsWith("set YAML_PATH=")) {
                    line = "set YAML_PATH=" + yamlAbs;
                } else if (line.trim().startsWith("set SWMM_INP=")) {
                    line = "set SWMM_INP=" + inpAbs;
                }
                content.append(line).append(System.lineSeparator());
            }
        }
        
        String targetBatPath = Paths.get(workspace.getProjectDir()).toAbsolutePath().normalize().resolve("run_simulation.bat").toString();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(targetBatPath))) {
            writer.write(content.toString());
        }
        
        workspace.setBatFilePath(targetBatPath);
        
        log.info("BAT文件已从模板生成并更新: 模板={}, 目标={}", batTemplatePath, targetBatPath);
        log.info("已设置参数: YAML_PATH={}, SWMM_INP={}", yamlAbs, inpAbs);
    }
    private Map<String, Object> processOutFile(Path outFilePath) {
        Map<String, Object> result = new HashMap<>();
        result.put("fileName", outFilePath.getFileName().toString());
        
        Path outputDir = outFilePath.getParent();
        String baseName = outFilePath.getFileName().toString().replaceAll("\\.out$", "");
        
        try {
            log.info("========== 使用 Python 解析 SWMM OUT 文件 ==========");
            log.info("OUT文件: {}", outFilePath);
            log.info("输出目录: {}", outputDir);
            
            String pythonScriptPath = Paths.get(
                    urbanFloodFileManager.getSimulationProperties().getUrbanFloodTemplatePath(),
                    "swmm_out_extractor.py"
            ).normalize().toString();
            
            log.info("Python脚本路径: {}", pythonScriptPath);
            
            String pythonExecutable = simulationProperties.getPythonExecutable();
            if (pythonExecutable == null || pythonExecutable.trim().isEmpty()) {
                pythonExecutable = "python";
            }
            log.info("Python可执行文件: {}", pythonExecutable);
            
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    pythonScriptPath,
                    "--out-file", outFilePath.toString(),
                    "--output-dir", outputDir.toString(),
                    "--prefix", baseName
            );
            pb.redirectErrorStream(false);
            
            Process process = pb.start();
            
            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();
            
            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutBuilder.append(line).append("\n");
                        log.info("[Python stdout] {}", line);
                    }
                } catch (Exception e) {
                    log.error("读取stdout失败", e);
                }
            });
            
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrBuilder.append(line).append("\n");
                        log.info("[Python stderr] {}", line);
                    }
                } catch (Exception e) {
                    log.error("读取stderr失败", e);
                }
            });
            
            stdoutThread.start();
            stderrThread.start();
            
            int exitCode = process.waitFor();
            
            stdoutThread.join(1000);
            stderrThread.join(1000);
            
            if (exitCode == 0) {
                log.info("Python OUT解析成功");
                
                String jsonOutput = stdoutBuilder.toString().trim();
                Map<String, Object> pythonSummary = JSON_MAPPER.readValue(jsonOutput, Map.class);
                
                Path timeBasedJsonPath = outputDir.resolve(baseName + "_time_based.json");
                Path objectBasedJsonPath = outputDir.resolve(baseName + "_object_based.json");
                
                String timeBasedJson = new String(Files.readAllBytes(timeBasedJsonPath), StandardCharsets.UTF_8);
                String objectBasedJson = new String(Files.readAllBytes(objectBasedJsonPath), StandardCharsets.UTF_8);
                
                Map<String, Object> timeBasedData = JSON_MAPPER.readValue(timeBasedJson, Map.class);
                Map<String, Object> objectBasedData = JSON_MAPPER.readValue(objectBasedJson, Map.class);
                
                result.put("processor", "python-swmmtoolbox");
                result.put("timeBasedData", timeBasedData);
                result.put("objectBasedData", objectBasedData);
                result.put("summary", pythonSummary);
                result.put("timeBasedDataInline", true);
                result.put("objectBasedDataInline", true);
                result.put("timeBasedJsonFile", timeBasedJsonPath.toString());
                result.put("objectBasedJsonFile", objectBasedJsonPath.toString());
                result.put("outputDirectory", outputDir.toString());
                
                log.info("========== Python OUT解析完成 ==========");
                log.info("时间点数: {}", pythonSummary.get("time_point_count"));
                log.info("总对象数: {}", pythonSummary.get("total_objects"));
                log.info("对象类型统计: {}", pythonSummary.get("object_type_counts"));
                
                if (stderrBuilder.length() > 0) {
                    log.info("Python处理日志:\n{}", stderrBuilder.toString());
                }
            } else {
                log.error("========== Python OUT解析失败 ==========");
                log.error("退出代码: {}", exitCode);
                log.error("标准输出:\n{}", stdoutBuilder);
                log.error("标准错误:\n{}", stderrBuilder);
                log.error("=========================================");
                
                result.put("processor", "python-error");
                result.put("error", "Python解析失败,退出代码: " + exitCode);
                result.put("pythonStdout", stdoutBuilder.toString());
                result.put("pythonStderr", stderrBuilder.toString());
            }
            
        } catch (Exception e) {
            log.error("========== 调用Python解析OUT文件异常 ==========");
            log.error("异常类型: {}", e.getClass().getName());
            log.error("异常消息: {}", e.getMessage());
            log.error("详细堆栈:", e);
            log.error("===============================================");
            
            result.put("processor", "python-exception");
            result.put("error", "Python调用异常: " + e.getMessage());
            result.put("fallbackData", parseOutFileFallback(outFilePath));
        }

        return result;
    }
    private String formatTime(double seconds) {
        int totalSeconds = (int) seconds;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int secs = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
    private Map<String, Object> parseOutFileFallback(Path outFilePath) {
        Map<String, Object> fallback = new HashMap<>();

        try {
            log.info("使用 Java 简化回退逻辑解析 OUT 文件: {}", outFilePath);

            fallback.put("status", "fallback");
            fallback.put("message", "无法解析SWMM OUT二进制文件，使用简化数据");
            fallback.put("timeSeriesData", Collections.emptyList());
            fallback.put("totalRecords", 0);

        } catch (Exception e) {
            log.error("回退解析 OUT 文件时发生错误: {}", e.getMessage(), e);
            fallback.put("error", "回退解析 OUT 文件失败: " + e.getMessage());
        }

        return fallback;
    }
    @Deprecated
    @SuppressWarnings("unused")
    private Map<String, Object> runSwmmOutExtractor(Path outFilePath) throws Exception {
        String pythonExecutable = simulationProperties.getPythonExecutable();
        if (pythonExecutable == null || pythonExecutable.trim().isEmpty()) {
            throw new IllegalStateException("未配置 Python 解释器路径(simulation.pythonExecutable)");
        }

        String templateRoot = simulationProperties.getUrbanFloodTemplatePath();
        if (templateRoot == null || templateRoot.trim().isEmpty()) {
            throw new IllegalStateException("未配置 ANUGA-SWMM 模板目录(simulation.urbanFloodTemplatePath)");
        }

        Path scriptPath = Paths.get(templateRoot, "swmm_out_extractor.py");
        if (!Files.exists(scriptPath)) {
            throw new FileNotFoundException("未找到 SWMM 后处理脚本: " + scriptPath);
        }

        Path outputDir = outFilePath.getParent();
        if (outputDir == null) {
            outputDir = Paths.get(templateRoot);
        }

        String fileName = outFilePath.getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String prefix = baseName + "_extracted";

        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExecutable,
                scriptPath.toString(),
                "--out-file", outFilePath.toString(),
                "--output-dir", outputDir.toString(),
                "--prefix", prefix,
                "--max-preview", "5"
        );
        processBuilder.directory(outputDir.toFile());
        processBuilder.redirectErrorStream(false);

        log.info("调用 Python 脚本进行 OUT 文件后处理: {}", scriptPath);
        log.info("Python 命令: {} {} --out-file {} --output-dir {} --prefix {} --max-preview 5", 
                pythonExecutable, scriptPath, outFilePath, outputDir, prefix);
        
        Process process = processBuilder.start();

        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());

        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        
        log.info("Python 脚本执行完成: finished={}", finished);
        
        if (!finished) {
            process.destroyForcibly();
            log.error("Python 脚本处理超时(>15分钟)，已强制终止进程");
            throw new IllegalStateException("Python 脚本处理超时(>15分钟)");
        }

        int exitCode = process.exitValue();
        log.info("Python 脚本退出码: {}", exitCode);
        
        if (stdout != null && !stdout.trim().isEmpty()) {
            log.info("Python 脚本 stdout (前500字符): {}", 
                    stdout.length() > 500 ? stdout.substring(0, 500) : stdout);
        } else {
            log.warn("Python 脚本未产生 stdout 输出");
        }
        
        if (stderr != null && !stderr.trim().isEmpty()) {
            log.warn("Python 脚本 stderr: {}", stderr);
        }
        
        if (exitCode != 0) {
            String errorMsg = String.format(
                    "Python 脚本执行失败(exit=%d): stdout=[%s], stderr=[%s]", 
                    exitCode,
                    stdout == null ? "" : (stdout.length() > 200 ? stdout.substring(0, 200) + "..." : stdout),
                    stderr == null ? "" : stderr.trim());
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (stdout == null || stdout.trim().isEmpty()) {
            log.error("Python 脚本未返回任何结果，stderr: {}", stderr);
            throw new IllegalStateException("Python 脚本未返回任何结果");
        }

        Map<String, Object> summary = JSON_MAPPER.readValue(stdout.trim(), Map.class);

        Map<String, Object> result = new HashMap<>();
        result.put("processor", "python");
        result.put("summary", summary);

        if (stderr != null && !stderr.trim().isEmpty()) {
            String logSnippet = stderr.length() > 4000 ? stderr.substring(stderr.length() - 4000) : stderr;
            result.put("scriptLog", logSnippet);
        }

        appendJsonReference(result, summary, "time_based_json", "timeBasedJsonFile", "timeBasedData");
        appendJsonReference(result, summary, "object_based_json", "objectBasedJsonFile", "objectBasedData");

        return result;
    }
    @Deprecated
    @SuppressWarnings("unused")
    private void appendJsonReference(Map<String, Object> result,
                                     Map<String, Object> summary,
                                     String summaryKey,
                                     String pathKey,
                                     String inlineKey) {
        Object pathObj = summary.get(summaryKey);
        if (!(pathObj instanceof String)) {
            return;
        }

        Path jsonPath = Paths.get((String) pathObj);
        result.put(pathKey, jsonPath.toString());

        Object inlineData = loadJsonIfSmall(jsonPath, 2 * 1024 * 1024L);
        if (inlineData != null) {
            result.put(inlineKey, inlineData);
            result.put(inlineKey + "Inline", true);
        } else {
            result.put(inlineKey + "Inline", false);
        }
    }
    @Deprecated
    @SuppressWarnings("unused")
    private String readStream(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
            }
            return builder.toString();
        }
    }
    @Deprecated
    @SuppressWarnings("unused")
    private Object loadJsonIfSmall(Path jsonPath, long maxSizeBytes) {
        try {
            if (Files.exists(jsonPath) && Files.size(jsonPath) <= maxSizeBytes) {
                try (InputStream inputStream = Files.newInputStream(jsonPath)) {
                    return JSON_MAPPER.readValue(inputStream, Object.class);
                }
            }
        } catch (Exception e) {
            log.warn("加载 JSON 预览失败: {}", e.getMessage());
        }
        return null;
    }
    private Map<String, Object> processSWWFile(Path swwFilePath, String outputDir,
                                                BiConsumer<Integer, Integer> frameProgressCallback) throws Exception {
        Map<String, Object> result = new HashMap<>();
        
        log.info("开始处理SWW文件: {}", swwFilePath);
        
        String swwFileName = swwFilePath.getFileName().toString();
        
        double gridSize = 10.0;
        
        try (NetcdfFile ncfile = NetcdfFiles.open(swwFilePath.toString())) {
            
            Variable xVar = ncfile.findVariable("x");
            Variable yVar = ncfile.findVariable("y");
            Variable timeVar = ncfile.findVariable("time");
            Variable stageVar = ncfile.findVariable("stage");
            Variable elevationVar = ncfile.findVariable("elevation");
            
            if (xVar == null || yVar == null || timeVar == null || 
                stageVar == null || elevationVar == null) {
                throw new Exception("SWW文件缺少必要的变量");
            }
            
            Array xArray = xVar.read();
            Array yArray = yVar.read();
            Array timeArray = timeVar.read();
            
            int nodeCount = (int) xArray.getSize();
            int timeSteps = (int) timeArray.getSize();
            
            log.info("SWW文件信息: 节点数={}, 时间步数={}", nodeCount, timeSteps);
            
            double[] x = arrayToDouble1D(xArray);
            double[] y = arrayToDouble1D(yArray);
            double[] time = arrayToDouble1D(timeArray);
            MeshInterpolationIndex meshIndex = buildSwwMeshInterpolationIndex(ncfile, swwFilePath, outputDir, x, y, nodeCount);
            
            Array stageArray = stageVar.read();
            Array elevationArray = elevationVar.read();
            
            Variable xMomVar = ncfile.findVariable("xmomentum");
            Variable yMomVar = ncfile.findVariable("ymomentum");
            
            Array xMomArray = xMomVar != null ? xMomVar.read() : null;
            Array yMomArray = yMomVar != null ? yMomVar.read() : null;
            
            double[][] depth = calculateDepthFromNetCDF(stageArray, elevationArray, timeSteps, nodeCount);
            GlobalMaxValues maxValues = calculateGlobalMaxValuesFromNetCDF(
                depth, xMomArray, yMomArray, timeSteps, nodeCount);
            
            log.info("全局最大值: 水深={}, X动量={}, Y动量={}", 
                maxValues.maxDepth, maxValues.maxXMom, maxValues.maxYMom);
            
            double xMin = Arrays.stream(x).min().orElse(0);
            double xMax = Arrays.stream(x).max().orElse(0);
            double yMin = Arrays.stream(y).min().orElse(0);
            double yMax = Arrays.stream(y).max().orElse(0);
            
            Map<String, Object> gridInfo = createRegularGrid(xMin, xMax, yMin, yMax, gridSize);
            int gridXCount = (int) gridInfo.get("gridXCount");
            int gridYCount = (int) gridInfo.get("gridYCount");
            double[] gridX = (double[]) gridInfo.get("gridX");
            double[] gridY = (double[]) gridInfo.get("gridY");
            
            log.info("规则网格: {}x{} 像素", gridXCount, gridYCount);
            
            List<Map<String, Object>> imageFrames = new ArrayList<>();
            int maxFramesToProcess = timeSteps;
            
            Path swwImageDir = Paths.get(outputDir, "sww_frames");
            if (!Files.exists(swwImageDir)) {
                Files.createDirectories(swwImageDir);
                log.info("创建SWW图像输出目录: {}", swwImageDir);
            }
            
            String baseFileName = swwFileName.replaceAll("\\.sww$", "");
            
            for (int frameIdx = 0; frameIdx < maxFramesToProcess; frameIdx++) {
                double timeValue = time[frameIdx];
                
                double[] depthAtTime = extractTimeSliceFromArray(depth, frameIdx, nodeCount);
                double[] xMomAtTime = xMomArray != null ? 
                    extractTimeSliceFromNetCDF(xMomArray, frameIdx, nodeCount) : null;
                double[] yMomAtTime = yMomArray != null ? 
                    extractTimeSliceFromNetCDF(yMomArray, frameIdx, nodeCount) : null;
                
                GridDataResult gridData = interpolateToRegularGrid(
                    x, y, depthAtTime, xMomAtTime, yMomAtTime, 
                    gridX, gridY, gridXCount, gridYCount, meshIndex);

                FrameStats frameStats = computeFrameStats(gridData);
                
                BufferedImage image = createRGBAImage(gridData, maxValues, gridXCount, gridYCount);
                
                String frameFileName = String.format("%s_frame_%04d.png", baseFileName, frameIdx);
                Path frameFilePath = swwImageDir.resolve(frameFileName);
                ImageIO.write(image, "PNG", frameFilePath.toFile());
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);
                byte[] imageBytes = baos.toByteArray();
                String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                
                Map<String, Object> frameData = new HashMap<>();
                frameData.put("frame", frameIdx);
                frameData.put("time", timeValue);
                frameData.put("filePath", frameFilePath.toString());
                frameData.put("base64", base64Image);
                frameData.put("dataUrl", "data:image/png;base64," + base64Image);
                frameData.put("sizeBytes", imageBytes.length);
                frameData.put("stats", frameStats.toMap());
                
                imageFrames.add(frameData);
                
                if ((frameIdx + 1) % 10 == 0 || frameIdx == maxFramesToProcess - 1) {
                    log.info("已处理 {}/{} 帧，已保存到: {}", frameIdx + 1, maxFramesToProcess, swwImageDir);
                }

                if (frameProgressCallback != null &&
                        ((frameIdx + 1) % 5 == 0 || frameIdx == maxFramesToProcess - 1)) {
                    frameProgressCallback.accept(frameIdx + 1, maxFramesToProcess);
                }
            }
            
            result.put("filename", swwFileName);
            result.put("nodeCount", nodeCount);
            result.put("totalTimeSteps", timeSteps);
            result.put("processedFrames", maxFramesToProcess);
            result.put("gridSize", gridSize);
            result.put("gridWidth", gridXCount);
            result.put("gridHeight", gridYCount);
            result.put("maxDepth", maxValues.maxDepth);
            result.put("maxXMomentum", maxValues.maxXMom);
            result.put("maxYMomentum", maxValues.maxYMom);
            result.put("meshInterpolation", meshIndex.toMap());
            Map<String, Object> momentumFlags = new HashMap<>();
            momentumFlags.put("xmomentum", xMomArray != null);
            momentumFlags.put("ymomentum", yMomArray != null);
            result.put("hasMomentum", momentumFlags);
            result.put("timeRange", new double[]{time[0], time[time.length - 1]});
            result.put("imageFrames", imageFrames);
            result.put("imageOutputDir", swwImageDir.toString());
            result.put("status", "success");
            result.put("message", "SWW文件处理完成");
            
            log.info("SWW文件处理完成: {} 帧图像已生成", imageFrames.size());
            
            try {
                Map<String, Object> imgsJsonData = new HashMap<>();

                Path gridInfoPath = swwImageDir.resolve(baseFileName + "_grid_info.txt");
                try {
                    List<String> gridInfoLines = Arrays.asList(
                            "grid_size=" + gridSize,
                            "raster_width=" + gridXCount,
                            "raster_height=" + gridYCount,
                            "x_range=[" + String.format("%.2f", xMin) + ", " + String.format("%.2f", xMax) + "]",
                            "y_range=[" + String.format("%.2f", yMin) + ", " + String.format("%.2f", yMax) + "]",
                            "time_steps=" + timeSteps,
                            "time_range=[" + String.format("%.2f", time[0]) + ", " + String.format("%.2f", time[time.length - 1]) + "]"
                    );
                    Files.write(gridInfoPath, gridInfoLines, StandardCharsets.UTF_8);
                } catch (Exception ignore) {
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("created_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
                metadata.put("total_images", imageFrames.size());
                metadata.put("directory", swwImageDir.toString());
                metadata.put("description", "SWW深度栅格图像Base64数据集");
                Map<String, Object> maxValueMap = new HashMap<>();
                maxValueMap.put("depth", maxValues.maxDepth);
                maxValueMap.put("x_momentum", maxValues.maxXMom);
                maxValueMap.put("y_momentum", maxValues.maxYMom);
                metadata.put("max_values", maxValueMap);
                metadata.put("mesh_interpolation", meshIndex.toMap());

                Map<String, Object> hasMomentumMap = new HashMap<>();
                hasMomentumMap.put("xmomentum", xMomArray != null);
                hasMomentumMap.put("ymomentum", yMomArray != null);
                metadata.put("has_momentum", hasMomentumMap);

                Map<String, Object> channelMapping = new HashMap<>();
                channelMapping.put("R", "depth (stage - elevation)");
                channelMapping.put("G", "xmomentum");
                channelMapping.put("B", "ymomentum");
                channelMapping.put("A", "valid_mask");
                metadata.put("channel_mapping", channelMapping);

                Map<String, Object> unitsMap = new HashMap<>();
                unitsMap.put("depth", "m");
                unitsMap.put("momentum", "m^2/s");
                metadata.put("units", unitsMap);

                double timeStart = time[0];
                double timeEnd = time[time.length - 1];

                Map<String, Object> gridInfoForJson = new HashMap<>();
                gridInfoForJson.put("grid_size", gridSize);
                gridInfoForJson.put("raster_size", gridXCount + " × " + gridYCount + " 像素");
                gridInfoForJson.put("raster_width", gridXCount);
                gridInfoForJson.put("raster_height", gridYCount);
                gridInfoForJson.put("x_range", Arrays.asList(xMin, xMax));
                gridInfoForJson.put("y_range", Arrays.asList(yMin, yMax));
                gridInfoForJson.put("time_steps", timeSteps);
                gridInfoForJson.put("time_range", String.format("%.2fs ~ %.2fs", timeStart, timeEnd));
                gridInfoForJson.put("time_range_array", Arrays.asList(timeStart, timeEnd));
                gridInfoForJson.put("info_file_path", gridInfoPath.toString());
                metadata.put("grid_info", gridInfoForJson);

                result.put("metadata", metadata);
                imgsJsonData.put("metadata", metadata);

                List<Map<String, Object>> imagesArray = new ArrayList<>();
                for (Map<String, Object> frameData : imageFrames) {
                    Map<String, Object> imageEntry = new HashMap<>();
                    imageEntry.put("frame_number", frameData.get("frame"));
                    imageEntry.put("filename", Paths.get((String) frameData.get("filePath")).getFileName().toString());
                    imageEntry.put("time_value", frameData.get("time"));
                    imageEntry.put("base64_data", frameData.get("base64"));
                    imageEntry.put("data_url", frameData.get("dataUrl"));

                    Map<String, Object> imageInfo = new HashMap<>();
                    imageInfo.put("width", gridXCount);
                    imageInfo.put("height", gridYCount);
                    imageInfo.put("mode", "RGBA");
                    imageInfo.put("format", "PNG");
                    imageInfo.put("size_bytes", frameData.get("sizeBytes"));
                    imageEntry.put("image_info", imageInfo);

                    if (frameData.containsKey("stats")) {
                        imageEntry.put("stats", frameData.get("stats"));
                    }

                    imagesArray.add(imageEntry);
                }
                imgsJsonData.put("images", imagesArray);

                Path imgsJsonPath = swwImageDir.resolve("Imgs.json");
                String jsonContent = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(imgsJsonData);
                Files.write(imgsJsonPath, jsonContent.getBytes(StandardCharsets.UTF_8));

                log.info("Imgs.json文件已生成: {}", imgsJsonPath);
                result.put("imgsJsonPath", imgsJsonPath.toString());

            } catch (Exception jsonEx) {
                log.error("生成Imgs.json文件失败: {}", jsonEx.getMessage(), jsonEx);
                result.put("imgsJsonWarning", "Imgs.json生成失败: " + jsonEx.getMessage());
            }
            
        } catch (Exception e) {
            log.error("处理SWW文件失败: {}", e.getMessage(), e);
            result.put("status", "error");
            result.put("message", "SWW处理失败: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
        }
        
        return result;
    }
    private Map<String, Object> createRegularGrid(double xMin, double xMax, 
                                                  double yMin, double yMax, 
                                                  double gridSize) {
        Map<String, Object> gridInfo = new HashMap<>();
        
        int gridXCount = (int) Math.ceil((xMax - xMin) / gridSize) + 1;
        int gridYCount = (int) Math.ceil((yMax - yMin) / gridSize) + 1;
        
        double[] gridX = new double[gridXCount];
        double[] gridY = new double[gridYCount];
        
        for (int i = 0; i < gridXCount; i++) {
            gridX[i] = xMin + i * gridSize;
        }
        
        for (int i = 0; i < gridYCount; i++) {
            gridY[i] = yMin + i * gridSize;
        }
        
        gridInfo.put("xMin", xMin);
        gridInfo.put("xMax", xMax);
        gridInfo.put("yMin", yMin);
        gridInfo.put("yMax", yMax);
        gridInfo.put("gridSize", gridSize);
        gridInfo.put("gridXCount", gridXCount);
        gridInfo.put("gridYCount", gridYCount);
        gridInfo.put("gridX", gridX);
        gridInfo.put("gridY", gridY);
        
        return gridInfo;
    }
    private double[][] calculateDepthFromNetCDF(Array stageArray, Array elevationArray, 
                                                int timeSteps, int nodeCount) {
        double[][] depth = new double[timeSteps][nodeCount];
        Index stageIndex = stageArray.getIndex();
        Index elevIndex = elevationArray.getIndex();
        
        boolean elevIsTimeSeries = elevationArray.getShape().length > 1;
        
        for (int t = 0; t < timeSteps; t++) {
            for (int n = 0; n < nodeCount; n++) {
                double stage = stageArray.getDouble(stageIndex.set(t, n));
                double elev = elevIsTimeSeries ? 
                    elevationArray.getDouble(elevIndex.set(t, n)) :
                    elevationArray.getDouble(elevIndex.set(n));
                
                depth[t][n] = Math.max(0, stage - elev);
            }
        }
        
        return depth;
    }
    private GlobalMaxValues calculateGlobalMaxValuesFromNetCDF(double[][] depth, 
                                                               Array xMomArray, 
                                                               Array yMomArray,
                                                               int timeSteps, 
                                                               int nodeCount) {
        GlobalMaxValues maxValues = new GlobalMaxValues();
        
        maxValues.maxDepth = Arrays.stream(depth)
            .flatMapToDouble(Arrays::stream)
            .max().orElse(0);
        
        if (xMomArray != null) {
            maxValues.maxXMom = Math.abs(findArrayMaxAbs(xMomArray, timeSteps, nodeCount));
        }
        
        if (yMomArray != null) {
            maxValues.maxYMom = Math.abs(findArrayMaxAbs(yMomArray, timeSteps, nodeCount));
        }
        
        return maxValues;
    }
    
    private double[] arrayToDouble1D(Array array) {
        int size = (int) array.getSize();
        double[] result = new double[size];
        IndexIterator iterator = array.getIndexIterator();
        int idx = 0;
        while (iterator.hasNext() && idx < size) {
            result[idx++] = iterator.getDoubleNext();
        }
        return result;
    }
    private double findArrayMaxAbs(Array array, int timeSteps, int nodeCount) {
        Index index = array.getIndex();
        double maxAbs = 0;
        
        for (int t = 0; t < timeSteps; t++) {
            for (int n = 0; n < nodeCount; n++) {
                double value = Math.abs(array.getDouble(index.set(t, n)));
                if (value > maxAbs) {
                    maxAbs = value;
                }
            }
        }
        
        return maxAbs;
    }
    private double[] extractTimeSliceFromArray(double[][] data, int timeIndex, int nodeCount) {
        return data[timeIndex];
    }
    private double[] extractTimeSliceFromNetCDF(Array array, int timeIndex, int nodeCount) {
        double[] slice = new double[nodeCount];
        Index index = array.getIndex();
        
        for (int n = 0; n < nodeCount; n++) {
            slice[n] = array.getDouble(index.set(timeIndex, n));
        }
        
        return slice;
    }
    private MeshInterpolationIndex buildSwwMeshInterpolationIndex(NetcdfFile ncfile,
                                                                  Path swwFilePath,
                                                                  String outputDir,
                                                                  double[] x,
                                                                  double[] y,
                                                                  int nodeCount) {
        try {
            List<int[]> triangles = readSwwVolumeTriangles(ncfile.findVariable("volumes"), nodeCount);
            String source = "sww_volumes";

            if (triangles.isEmpty()) {
                Path triCsv = resolveTinTriangleCsv(swwFilePath, outputDir);
                if (triCsv != null) {
                    triangles = readTinTrianglesFromCsv(triCsv, nodeCount);
                    source = triCsv.getFileName().toString();
                } else {
                    source = "none";
                }
            }

            MeshInterpolationIndex meshIndex = buildMeshInterpolationIndex(triangles, source, x, y, nodeCount);
            if (meshIndex.hasTriangles()) {
                log.info("SWW后处理使用原始三角网插值: source={}, triangles={}, skipped={}",
                        meshIndex.source, meshIndex.triangleCount, meshIndex.skippedCount);
            }
            return meshIndex;
        } catch (Exception e) {
            log.warn("构建SWW原始三角网插值索引失败，将退回旧Delaunay插值: {}", e.getMessage(), e);
            return MeshInterpolationIndex.empty("unavailable");
        }
    }

    private List<int[]> readSwwVolumeTriangles(Variable volumesVar, int nodeCount) throws IOException {
        if (volumesVar == null) {
            return Collections.emptyList();
        }

        Array volumesArray = volumesVar.read();
        int[] shape = volumesArray.getShape();
        if (shape.length != 2 || (shape[0] < 3 && shape[1] < 3)) {
            log.warn("SWW volumes变量维度不符合三角网格式: {}", Arrays.toString(shape));
            return Collections.emptyList();
        }

        boolean rowMajor = shape[1] >= 3;
        int triangleCount = rowMajor ? shape[0] : shape[1];
        Index index = volumesArray.getIndex();
        List<int[]> rawTriangles = new ArrayList<>(triangleCount);
        for (int i = 0; i < triangleCount; i++) {
            int p0 = rowMajor ? volumesArray.getInt(index.set(i, 0)) : volumesArray.getInt(index.set(0, i));
            int p1 = rowMajor ? volumesArray.getInt(index.set(i, 1)) : volumesArray.getInt(index.set(1, i));
            int p2 = rowMajor ? volumesArray.getInt(index.set(i, 2)) : volumesArray.getInt(index.set(2, i));
            rawTriangles.add(new int[]{p0, p1, p2});
        }
        return normalizeAndFilterTriangleIndices(rawTriangles, nodeCount);
    }

    private Path resolveTinTriangleCsv(Path swwFilePath, String outputDir) {
        Set<Path> candidates = new LinkedHashSet<>();
        if (outputDir != null && !outputDir.trim().isEmpty()) {
            candidates.add(Paths.get(outputDir).resolve("p_a_v_tri.csv"));
        }
        Path swwParent = swwFilePath.getParent();
        if (swwParent != null) {
            candidates.add(swwParent.resolve("p_a_v_tri.csv"));
            Path parent = swwParent.getParent();
            if (parent != null) {
                candidates.add(parent.resolve("p_a_v_tri.csv"));
            }
        }
        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<int[]> readTinTrianglesFromCsv(Path triCsv, int nodeCount) throws IOException {
        List<int[]> rawTriangles = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(triCsv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.toLowerCase(Locale.ROOT).startsWith("tinid")) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 4) {
                    continue;
                }
                try {
                    rawTriangles.add(new int[]{
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()),
                            Integer.parseInt(parts[3].trim())
                    });
                } catch (NumberFormatException ignored) {
                    // Skip non-data rows.
                }
            }
        }
        return normalizeAndFilterTriangleIndices(rawTriangles, nodeCount);
    }

    private List<int[]> normalizeAndFilterTriangleIndices(List<int[]> rawTriangles, int nodeCount) {
        if (rawTriangles == null || rawTriangles.isEmpty()) {
            return Collections.emptyList();
        }

        int minIndex = Integer.MAX_VALUE;
        int maxIndex = Integer.MIN_VALUE;
        for (int[] tri : rawTriangles) {
            if (tri == null || tri.length < 3) {
                continue;
            }
            minIndex = Math.min(minIndex, Math.min(tri[0], Math.min(tri[1], tri[2])));
            maxIndex = Math.max(maxIndex, Math.max(tri[0], Math.max(tri[1], tri[2])));
        }

        int shift = minIndex >= 1 && maxIndex == nodeCount ? -1 : 0;
        List<int[]> triangles = new ArrayList<>(rawTriangles.size());
        for (int[] tri : rawTriangles) {
            if (tri == null || tri.length < 3) {
                continue;
            }
            int p0 = tri[0] + shift;
            int p1 = tri[1] + shift;
            int p2 = tri[2] + shift;
            if (p0 < 0 || p1 < 0 || p2 < 0 || p0 >= nodeCount || p1 >= nodeCount || p2 >= nodeCount) {
                continue;
            }
            if (p0 == p1 || p1 == p2 || p0 == p2) {
                continue;
            }
            triangles.add(new int[]{p0, p1, p2});
        }
        return triangles;
    }

    private MeshInterpolationIndex buildMeshInterpolationIndex(List<int[]> triangleIndices,
                                                               String source,
                                                               double[] x,
                                                               double[] y,
                                                               int nodeCount) {
        if (triangleIndices == null || triangleIndices.isEmpty()) {
            return MeshInterpolationIndex.empty(source);
        }

        STRtree tree = new STRtree();
        int inserted = 0;
        int skipped = 0;
        for (int[] tri : triangleIndices) {
            if (tri == null || tri.length < 3) {
                skipped++;
                continue;
            }
            int p0 = tri[0];
            int p1 = tri[1];
            int p2 = tri[2];
            if (p0 < 0 || p1 < 0 || p2 < 0 || p0 >= nodeCount || p1 >= nodeCount || p2 >= nodeCount) {
                skipped++;
                continue;
            }
            Coordinate a = new Coordinate(x[p0], y[p0]);
            Coordinate b = new Coordinate(x[p1], y[p1]);
            Coordinate c = new Coordinate(x[p2], y[p2]);
            if (!isFiniteCoordinate(a) || !isFiniteCoordinate(b) || !isFiniteCoordinate(c)) {
                skipped++;
                continue;
            }
            if (Math.abs(triangleArea2(a, b, c)) < 1e-12) {
                skipped++;
                continue;
            }

            MeshTriangle meshTriangle = new MeshTriangle(p0, p1, p2, a, b, c);
            tree.insert(meshTriangle.envelope, meshTriangle);
            inserted++;
        }

        if (inserted == 0) {
            return MeshInterpolationIndex.empty(source);
        }
        tree.build();
        return new MeshInterpolationIndex(tree, source, inserted, skipped);
    }

    private boolean isFiniteCoordinate(Coordinate coordinate) {
        return coordinate != null &&
                Double.isFinite(coordinate.x) &&
                Double.isFinite(coordinate.y);
    }

    private double triangleArea2(Coordinate a, Coordinate b, Coordinate c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private GridDataResult interpolateToRegularGrid(double[] x, double[] y,
                                                    double[] depthData,
                                                    double[] xMomData,
                                                    double[] yMomData,
                                                    double[] gridX, double[] gridY,
                                                    int gridXCount, int gridYCount,
                                                    MeshInterpolationIndex meshIndex) {
        if (meshIndex != null && meshIndex.hasTriangles()) {
            GridDataResult gridData = new GridDataResult();
            gridData.depth = new double[gridYCount][gridXCount];
            gridData.xMomentum = new double[gridYCount][gridXCount];
            gridData.yMomentum = new double[gridYCount][gridXCount];
            gridData.validMask = new boolean[gridYCount][gridXCount];

            IntStream.range(0, gridYCount).parallel().forEach(j -> {
                double gy = gridY[j];
                for (int i = 0; i < gridXCount; i++) {
                    double gx = gridX[i];
                    InterpolationResult r = interpolateLinearAtPoint(gx, gy, meshIndex, depthData, xMomData, yMomData);
                    gridData.depth[j][i] = r.depth;
                    gridData.xMomentum[j][i] = r.xMom;
                    gridData.yMomentum[j][i] = r.yMom;
                    gridData.validMask[j][i] = r.valid;
                }
            });

            return gridData;
        }

        log.warn("SWW原始三角网不可用，退回旧Delaunay点集插值。该模式可能会填充孔洞区域。");
        return interpolateToRegularGridWithDelaunay(
                x, y, depthData, xMomData, yMomData, gridX, gridY, gridXCount, gridYCount);
    }

    private GridDataResult interpolateToRegularGridWithDelaunay(double[] x, double[] y,
                                                    double[] depthData,
                                                    double[] xMomData, 
                                                    double[] yMomData,
                                                    double[] gridX, double[] gridY,
                                                    int gridXCount, int gridYCount) {

        List<Coordinate> coords = new ArrayList<>(x.length);
        Map<String, Integer> coordIndex = new HashMap<>(x.length * 2);
        for (int i = 0; i < x.length; i++) {
            Coordinate c = new Coordinate(x[i], y[i]);
            coords.add(c);
            coordIndex.put(c.x + "," + c.y, i);
        }

        DelaunayTriangulationBuilder builder = new DelaunayTriangulationBuilder();
        builder.setSites(coords);
        GeometryFactory gf = new GeometryFactory();
        Geometry trianglesGeom = builder.getTriangles(gf);

        List<Polygon> triangles = new ArrayList<>();
        if (trianglesGeom instanceof GeometryCollection) {
            GeometryCollection gc = (GeometryCollection) trianglesGeom;
            for (int k = 0; k < gc.getNumGeometries(); k++) {
                Geometry g = gc.getGeometryN(k);
                if (g instanceof Polygon) {
                    triangles.add((Polygon) g);
                }
            }
        }

        GridDataResult gridData = new GridDataResult();
        gridData.depth = new double[gridYCount][gridXCount];
        gridData.xMomentum = new double[gridYCount][gridXCount];
        gridData.yMomentum = new double[gridYCount][gridXCount];
        gridData.validMask = new boolean[gridYCount][gridXCount];

        IntStream.range(0, gridYCount).parallel().forEach(j -> {
            double gy = gridY[j];
            for (int i = 0; i < gridXCount; i++) {
                double gx = gridX[i];
                InterpolationResult r = interpolateLinearAtPoint(gx, gy, triangles, coordIndex, depthData, xMomData, yMomData);
                gridData.depth[j][i] = r.depth;
                gridData.xMomentum[j][i] = r.xMom;
                gridData.yMomentum[j][i] = r.yMom;
                gridData.validMask[j][i] = r.valid;
            }
        });

        return gridData;
    }
    private InterpolationResult interpolateLinearAtPoint(double gx, double gy,
                                                         MeshInterpolationIndex meshIndex,
                                                         double[] depthData,
                                                         double[] xMomData,
                                                         double[] yMomData) {
        InterpolationResult result = new InterpolationResult();
        Coordinate p = new Coordinate(gx, gy);

        for (Object item : meshIndex.query(gx, gy)) {
            if (!(item instanceof MeshTriangle)) {
                continue;
            }
            MeshTriangle tri = (MeshTriangle) item;
            Barycentric bc = computeBarycentric(p, tri.a, tri.b, tri.c);
            if (!bc.inside) {
                continue;
            }

            double depth = bc.w1 * depthData[tri.p0] + bc.w2 * depthData[tri.p1] + bc.w3 * depthData[tri.p2];
            double xmom = 0;
            double ymom = 0;
            if (xMomData != null) {
                xmom = bc.w1 * xMomData[tri.p0] + bc.w2 * xMomData[tri.p1] + bc.w3 * xMomData[tri.p2];
            }
            if (yMomData != null) {
                ymom = bc.w1 * yMomData[tri.p0] + bc.w2 * yMomData[tri.p1] + bc.w3 * yMomData[tri.p2];
            }

            result.valid = true;
            result.depth = Math.max(0, depth);
            result.xMom = xmom;
            result.yMom = ymom;
            return result;
        }

        result.valid = false;
        result.depth = 0;
        result.xMom = 0;
        result.yMom = 0;
        return result;
    }

    private InterpolationResult interpolateLinearAtPoint(double gx, double gy,
                                                         List<Polygon> triangles,
                                                         Map<String, Integer> coordIndex,
                                                         double[] depthData,
                                                         double[] xMomData,
                                                         double[] yMomData) {
        InterpolationResult result = new InterpolationResult();
        Coordinate p = new Coordinate(gx, gy);

        for (Polygon tri : triangles) {
            Coordinate[] cs = tri.getCoordinates();
            if (cs.length < 3) continue;
            Coordinate a = cs[0];
            Coordinate b = cs[1];
            Coordinate c = cs[2];

            Barycentric bc = computeBarycentric(p, a, b, c);
            if (!bc.inside) continue;

            Integer ia = coordIndex.get(a.x + "," + a.y);
            Integer ib = coordIndex.get(b.x + "," + b.y);
            Integer ic = coordIndex.get(c.x + "," + c.y);
            if (ia == null || ib == null || ic == null) continue;

            double depth = bc.w1 * depthData[ia] + bc.w2 * depthData[ib] + bc.w3 * depthData[ic];
            double xmom = 0;
            double ymom = 0;
            if (xMomData != null) {
                xmom = bc.w1 * xMomData[ia] + bc.w2 * xMomData[ib] + bc.w3 * xMomData[ic];
            }
            if (yMomData != null) {
                ymom = bc.w1 * yMomData[ia] + bc.w2 * yMomData[ib] + bc.w3 * yMomData[ic];
            }

            result.valid = true;
            result.depth = Math.max(0, depth);
            result.xMom = xmom;
            result.yMom = ymom;
            return result;
        }

        result.valid = false;
        result.depth = 0;
        result.xMom = 0;
        result.yMom = 0;
        return result;
    }

    private static class Barycentric {
        double w1;
        double w2;
        double w3;
        boolean inside;
    }
    private Barycentric computeBarycentric(Coordinate p, Coordinate a, Coordinate b, Coordinate c) {
        Barycentric res = new Barycentric();
        double denom = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
        if (Math.abs(denom) < 1e-12) {
            res.inside = false;
            return res;
        }
        double w1 = ((b.y - c.y) * (p.x - c.x) + (c.x - b.x) * (p.y - c.y)) / denom;
        double w2 = ((c.y - a.y) * (p.x - c.x) + (a.x - c.x) * (p.y - c.y)) / denom;
        double w3 = 1 - w1 - w2;

        res.w1 = w1;
        res.w2 = w2;
        res.w3 = w3;
        double eps = -1e-9;
        res.inside = w1 >= eps && w2 >= eps && w3 >= eps;
        return res;
    }
    private FrameStats computeFrameStats(GridDataResult gridData) {
        FrameStats stats = new FrameStats();
        double sumDepth = 0;
        int validCount = 0;

        double maxDepth = 0;
        double maxAbsXMom = 0;
        double maxAbsYMom = 0;

        int height = gridData.depth.length;
        int width = gridData.depth[0].length;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                if (!gridData.validMask[j][i]) {
                    continue;
                }
                double d = gridData.depth[j][i];
                double xm = gridData.xMomentum[j][i];
                double ym = gridData.yMomentum[j][i];

                if (d > maxDepth) maxDepth = d;
                if (Math.abs(xm) > maxAbsXMom) maxAbsXMom = Math.abs(xm);
                if (Math.abs(ym) > maxAbsYMom) maxAbsYMom = Math.abs(ym);

                sumDepth += d;
                validCount++;
            }
        }

        stats.maxDepth = maxDepth;
        stats.meanDepth = validCount > 0 ? sumDepth / validCount : 0;
        stats.maxAbsXMom = maxAbsXMom;
        stats.maxAbsYMom = maxAbsYMom;
        stats.validCount = validCount;
        stats.pixelCount = width * height;
        return stats;
    }
    private BufferedImage createRGBAImage(GridDataResult gridData, 
                                         GlobalMaxValues maxValues,
                                         int width, int height) {
        
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int pixelIndex = ((height - 1 - j) * width + i) * 4;
                
                if (gridData.validMask[j][i]) {
                    int r, g, b, a;
                    if (maxValues.maxDepth > 0) {
                        r = (int) Math.min(255, gridData.depth[j][i] / maxValues.maxDepth * 255);
                    } else {
                        r = 0;
                    }

                    if (maxValues.maxXMom > 0) {
                        double normalized = gridData.xMomentum[j][i] / maxValues.maxXMom;
                        g = (int) Math.min(255, Math.max(0, normalized * 127 + 127));
                    } else {
                        g = 127;
                    }

                    if (maxValues.maxYMom > 0) {
                        double normalized = gridData.yMomentum[j][i] / maxValues.maxYMom;
                        b = (int) Math.min(255, Math.max(0, normalized * 127 + 127));
                    } else {
                        b = 127;
                    }

                    a = gridData.depth[j][i] > 0 ? 255 : 0;

                    pixels[pixelIndex] = (byte) a;      // Alpha
                    pixels[pixelIndex + 1] = (byte) b;  // Blue
                    pixels[pixelIndex + 2] = (byte) g;  // Green
                    pixels[pixelIndex + 3] = (byte) r;  // Red
                } else {
                    pixels[pixelIndex] = 0;       // A
                    pixels[pixelIndex + 1] = 0;   // B
                    pixels[pixelIndex + 2] = 0;   // G
                    pixels[pixelIndex + 3] = 0;   // R
                }
            }
        }
        
        return image;
    }
    private static class SwmmInpGenerator {

        private static final Logger LOGGER = LoggerFactory.getLogger(SwmmInpGenerator.class);

        private double spatialTolerance = 0.01;
        private boolean useDemForDepth = true;
        private String demPath;
        private String rainfallCsvPath;
        private Integer simulationDuration = 180;

        private final List<NodeData> nodes = new ArrayList<>();
        private final List<LinkData> links = new ArrayList<>();
        private final Map<String, String> nodeIdMapping = new HashMap<>();
        private GridCoverage2D demCoverage;

        private final Map<String, String> nodeMapping = new HashMap<>();
        private final Map<String, String> linkMapping = new HashMap<>();
        private final Map<String, String> nodeTypeMapping = new HashMap<>();
        private final Map<String, String> shapeTypeMapping = new HashMap<>();

        public SwmmInpGenerator() {
            initializeFieldMappings();
        }
        public void applyCustomMappings(Map<String, String> customNodeMapping,
                                        Map<String, String> customLinkMapping,
                                        Map<String, String> customNodeTypeMapping,
                                        Map<String, String> customShapeTypeMapping) {
            overrideMapping(nodeMapping, customNodeMapping);
            overrideMapping(linkMapping, customLinkMapping);
            overrideMapping(nodeTypeMapping, customNodeTypeMapping);
            overrideMapping(shapeTypeMapping, customShapeTypeMapping);
        }

        private void overrideMapping(Map<String, String> target, Map<String, String> custom) {
            if (custom == null || custom.isEmpty()) {
                return;
            }
            custom.forEach((k, v) -> {
                if (k != null && v != null) {
                    String key = k.trim();
                    String val = v.trim();
                    if (!key.isEmpty() && !val.isEmpty()) {
                        target.put(key, val);
                    }
                }
            });
        }

        public void setSpatialTolerance(double spatialTolerance) {
            this.spatialTolerance = spatialTolerance;
        }

        public void setUseDemForDepth(boolean useDemForDepth) {
            this.useDemForDepth = useDemForDepth;
        }

        public boolean generateInpFile(List<String> nodeShpPaths, List<String> linkShpPaths,
                                       String outputInpPath, String demPath, String rainfallCsvPath, 
                                       Integer simulationDuration) {
            try {
                this.demPath = demPath;
                this.rainfallCsvPath = rainfallCsvPath;
                this.nodes.clear();
                this.links.clear();
                this.nodeIdMapping.clear();
                this.demCoverage = null;
                this.simulationDuration = simulationDuration;

                LOGGER.info("读取节点数据...");
                loadAndMergeNodes(nodeShpPaths);

                LOGGER.info("读取管线数据...");
                loadAndMergeLinks(linkShpPaths);

                LOGGER.info("更新管线节点引用...");
                updateLinkNodeReferences();

                LOGGER.info("验证管线有效性...");
                validateAndCleanLinks();

                LOGGER.info("去除重复管线...");
                removeDuplicateLinks();

                LOGGER.info("过滤孤立节点...");
                filterIsolatedNodes();

                LOGGER.info("检查节点类型...");
                ensureNodeTypes();

                LOGGER.info("根据起终点坐标和高程重新计算管线长度...");
                recalculateLinkLengths();


                LOGGER.info("使用DEM计算节点深度...");
                calculateDepthsFromDem();
               

                LOGGER.info("生成INP文件...");
                writeInpFile(outputInpPath);

                LOGGER.info("成功生成INP文件: {}，节点数: {}，管线数: {}", outputInpPath, nodes.size(), links.size());
                return true;
            } catch (Exception e) {
                LOGGER.error("INP文件生成失败", e);
                return false;
            }
        }

        private void initializeFieldMappings() {
            nodeMapping.clear();
            nodeMapping.put("name", "Name");
            nodeMapping.put("elevation", "Elevation");
            nodeMapping.put("node_type", "Type");
            nodeMapping.put("maxdepth", "maxDepth");
            nodeMapping.put("initdepth", "InitDepth");
            nodeMapping.put("surdepth", "SurDepth");
            nodeMapping.put("aponded", "APonded");

            linkMapping.clear();
            linkMapping.put("name", "Name");
            linkMapping.put("from_node", "FromNode");
            linkMapping.put("to_node", "ToNode");
            linkMapping.put("length", "Length");
            linkMapping.put("material", "Material");
            linkMapping.put("roughness", "Roughness");
            linkMapping.put("shape", "XsectShape");
            linkMapping.put("geom1", "Geom1");
            linkMapping.put("geom2", "Geom2");

            nodeTypeMapping.clear();
            nodeTypeMapping.put("检查井", "JUNCTION");
            nodeTypeMapping.put("出水口", "OUTFALL");
            nodeTypeMapping.put("连接点", "JUNCTION");
            nodeTypeMapping.put("入水口", "INLET");

            shapeTypeMapping.clear();
            shapeTypeMapping.put("直埋", "CIRCULAR");
            shapeTypeMapping.put("圆形", "CIRCULAR");
            shapeTypeMapping.put("沟道", "RECT_CLOSED");
            shapeTypeMapping.put("矩形", "RECT_CLOSED");
        }

        private void loadAndMergeNodes(List<String> nodeShpPaths) throws IOException {
            List<NodeData> allNodes = new ArrayList<>();
            for (String shpPath : nodeShpPaths) {
                LOGGER.info("读取节点文件: {}", shpPath);
                allNodes.addAll(readNodeShapefile(shpPath));
            }
            nodes.clear();
            mergeNodes(allNodes);
        }

        private List<NodeData> readNodeShapefile(String shpPath) throws IOException {
            String[] charsets = {"GBK", "UTF-8"};
            IOException lastException = null;
            
            for (String charset : charsets) {
                try {
                    List<NodeData> nodeList = readNodeShapefileWithCharset(shpPath, charset);
                    boolean hasGarbled = false;
                    for (NodeData node : nodeList) {
                        if (containsGarbledText(node.name) || containsGarbledText(node.nodeType)) {
                            hasGarbled = true;
                            break;
                        }
                    }
                    
                    if (!hasGarbled) {
                        log.info("使用{}编码成功读取节点Shapefile: {}", charset, shpPath);
                        return nodeList;
                    } else {
                        log.warn("使用{}编码读取到乱码,尝试下一种编码", charset);
                    }
                } catch (Exception e) {
                    log.warn("使用{}编码读取失败: {}", charset, e.getMessage());
                    lastException = new IOException("使用" + charset + "编码读取失败", e);
                }
            }
            
            throw new IOException("无法使用任何支持的编码读取Shapefile: " + shpPath, lastException);
        }
        
        private List<NodeData> readNodeShapefileWithCharset(String shpPath, String charset) throws IOException {
            List<NodeData> nodeList = new ArrayList<>();
            File file = new File(shpPath);
            Map<String, Object> map = new HashMap<>();
            map.put("url", file.toURI().toURL());
            map.put("charset", charset);

            DataStore dataStore = DataStoreFinder.getDataStore(map);
            if (dataStore == null) {
                throw new FileNotFoundException("无法读取Shapefile: " + shpPath);
            }
            try {
                String typeName = dataStore.getTypeNames()[0];
                FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
                FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();
                try (FeatureIterator<SimpleFeature> features = collection.features()) {
                    while (features.hasNext()) {
                        SimpleFeature feature = features.next();
                        NodeData node = new NodeData();
                        node.name = getStringAttribute(feature, nodeMapping.get("name"));
                        node.elevation = getDoubleAttribute(feature, nodeMapping.get("elevation"), 0.0);
                        node.nodeType = getStringAttribute(feature, nodeMapping.get("node_type"));
                        node.maxDepth = getDoubleAttribute(feature, nodeMapping.get("maxdepth"), 0.0);
                        Point point = (Point) feature.getDefaultGeometry();
                        node.x = point.getX();
                        node.y = point.getY();
                        node.originalName = node.name;
                        nodeList.add(node);
                    }
                }
            } finally {
                dataStore.dispose();
            }
            return nodeList;
        }

        private void mergeNodes(List<NodeData> allNodes) {
            Map<String, List<NodeData>> coordGroups = new HashMap<>();
            double tolerance = Math.max(spatialTolerance, 1e-6);
            int precision = Math.max(1, (int) (-Math.log10(tolerance)) + 1);

            for (NodeData node : allNodes) {
                String coordKey = String.format(Locale.US, "% ." + precision + "f_% ." + precision + "f", node.x, node.y);
                coordGroups.computeIfAbsent(coordKey.trim(), k -> new ArrayList<>()).add(node);
            }

            for (List<NodeData> group : coordGroups.values()) {
                if (group.size() > 1) {
                    group.sort(Comparator.comparing(n -> n.originalName == null ? "" : n.originalName));
                    NodeData keepNode = group.get(0);
                    for (int i = 1; i < group.size(); i++) {
                        NodeData removeNode = group.get(i);
                        double distance = Math.hypot(keepNode.x - removeNode.x, keepNode.y - removeNode.y);
                        if (distance <= tolerance) {
                            if (removeNode.originalName != null && keepNode.originalName != null) {
                                nodeIdMapping.put(removeNode.originalName, keepNode.originalName);
                            }
                        } else {
                            nodes.add(removeNode);
                        }
                    }
                    nodes.add(keepNode);
                } else if (!group.isEmpty()) {
                    nodes.add(group.get(0));
                }
            }

            ensureUniqueNodeNames();
        }

        private void ensureUniqueNodeNames() {
            Map<String, Long> nameCount = nodes.stream()
                    .filter(n -> n.name != null)
                    .collect(Collectors.groupingBy(n -> n.name, Collectors.counting()));

            Map<String, Integer> duplicateCounter = new HashMap<>();
            for (NodeData node : nodes) {
                if (node.name == null) {
                    node.name = UUID.randomUUID().toString();
                    continue;
                }
                long count = nameCount.getOrDefault(node.name, 0L);
                if (count > 1) {
                    int counter = duplicateCounter.getOrDefault(node.name, 0) + 1;
                    duplicateCounter.put(node.name, counter);
                    node.name = node.name + "_dup" + counter;
                }
            }
        }

        private void loadAndMergeLinks(List<String> linkShpPaths) throws IOException {
            links.clear();
            for (String shpPath : linkShpPaths) {
                LOGGER.info("读取管线文件: {}", shpPath);
                links.addAll(readLinkShapefile(shpPath));
            }
        }

        private List<LinkData> readLinkShapefile(String shpPath) throws IOException {
            String[] charsets = {"GBK", "UTF-8"};
            IOException lastException = null;
            
            for (String charset : charsets) {
                try {
                    List<LinkData> linkList = readLinkShapefileWithCharset(shpPath, charset);
                    boolean hasGarbled = false;
                    for (LinkData link : linkList) {
                        if (containsGarbledText(link.name) || containsGarbledText(link.material) || 
                            containsGarbledText(link.shape)) {
                            hasGarbled = true;
                            break;
                        }
                    }
                    
                    if (!hasGarbled) {
                        log.info("使用{}编码成功读取管线Shapefile: {}", charset, shpPath);
                        return linkList;
                    } else {
                        log.warn("使用{}编码读取到乱码,尝试下一种编码", charset);
                    }
                } catch (Exception e) {
                    log.warn("使用{}编码读取失败: {}", charset, e.getMessage());
                    lastException = new IOException("使用" + charset + "编码读取失败", e);
                }
            }
            
            throw new IOException("无法使用任何支持的编码读取Shapefile: " + shpPath, lastException);
        }
        
        private List<LinkData> readLinkShapefileWithCharset(String shpPath, String charset) throws IOException {
            List<LinkData> linkList = new ArrayList<>();
            File file = new File(shpPath);
            Map<String, Object> map = new HashMap<>();
            map.put("url", file.toURI().toURL());
            map.put("charset", charset);

            DataStore dataStore = DataStoreFinder.getDataStore(map);
            if (dataStore == null) {
                throw new FileNotFoundException("无法读取Shapefile: " + shpPath);
            }
            try {
                String typeName = dataStore.getTypeNames()[0];
                FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
                FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();
                try (FeatureIterator<SimpleFeature> features = collection.features()) {
                    while (features.hasNext()) {
                        SimpleFeature feature = features.next();
                        LinkData link = new LinkData();
                        link.name = getStringAttribute(feature, linkMapping.get("name"));
                        link.fromNode = getStringAttribute(feature, linkMapping.get("from_node"));
                        link.toNode = getStringAttribute(feature, linkMapping.get("to_node"));
                        link.length = getDoubleAttribute(feature, linkMapping.get("length"), 0.0);
                        link.material = getStringAttribute(feature, linkMapping.get("material"));
                        link.roughness = getDoubleAttribute(feature, linkMapping.get("roughness"), 0.013);
                        link.shape = getStringAttribute(feature, linkMapping.get("shape"));
                        link.geom1 = getDoubleAttribute(feature, linkMapping.get("geom1"), 1.0);
                        link.geom2 = getDoubleAttribute(feature, linkMapping.get("geom2"), 0.0);
                        Geometry geometry = (Geometry) feature.getDefaultGeometry();
                        if (geometry instanceof LineString) {
                            LineString lineString = (LineString) geometry;
                            if (link.length == 0.0) {
                                link.length = lineString.getLength();
                            }
                        }
                        linkList.add(link);
                    }
                }
            } finally {
                dataStore.dispose();
            }
            return linkList;
        }
        private boolean containsGarbledText(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            
            if (text.contains("�")) {
                return true;
            }
            
            int consecutiveLatin1Extended = 0;
            for (char c : text.toCharArray()) {
                if (c >= 0xC0 && c <= 0xFF) {
                    consecutiveLatin1Extended++;
                    if (consecutiveLatin1Extended >= 2) {
                        return true;
                    }
                } else {
                    consecutiveLatin1Extended = 0;
                }
            }
            
            return false;
        }

        private void updateLinkNodeReferences() {
            if (nodeIdMapping.isEmpty()) {
                return;
            }
            Map<String, String> resolvedMapping = new HashMap<>();
            for (String oldId : nodeIdMapping.keySet()) {
                resolvedMapping.put(oldId, resolveMappingChain(oldId, new HashSet<>()));
            }
            for (LinkData link : links) {
                if (resolvedMapping.containsKey(link.fromNode)) {
                    link.fromNode = resolvedMapping.get(link.fromNode);
                }
                if (resolvedMapping.containsKey(link.toNode)) {
                    link.toNode = resolvedMapping.get(link.toNode);
                }
            }
        }

        private String resolveMappingChain(String nodeId, Set<String> visited) {
            if (visited.contains(nodeId) || !nodeIdMapping.containsKey(nodeId)) {
                return nodeId;
            }
            visited.add(nodeId);
            String target = nodeIdMapping.get(nodeId);
            return resolveMappingChain(target, visited);
        }

        private void validateAndCleanLinks() {
            Set<String> validNodes = nodes.stream()
                    .map(n -> n.name)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            links.removeIf(link -> !validNodes.contains(link.fromNode) || !validNodes.contains(link.toNode));
        }

        private void removeDuplicateLinks() {
            Map<String, List<LinkData>> connectionGroups = links.stream()
                    .collect(Collectors.groupingBy(link -> {
                        String[] pair = {link.fromNode, link.toNode};
                        Arrays.sort(pair, Comparator.nullsFirst(String::compareTo));
                        return (pair[0] == null ? "" : pair[0]) + "_" + (pair[1] == null ? "" : pair[1]);
                    }));
            List<LinkData> uniqueLinks = new ArrayList<>();
            for (List<LinkData> group : connectionGroups.values()) {
                if (!group.isEmpty()) {
                    uniqueLinks.add(group.get(0));
                }
            }
            links.clear();
            links.addAll(uniqueLinks);
        }

        private void filterIsolatedNodes() {
            Set<String> connectedNodes = new HashSet<>();
            for (LinkData link : links) {
                if (link.fromNode != null) {
                    connectedNodes.add(link.fromNode);
                }
                if (link.toNode != null) {
                    connectedNodes.add(link.toNode);
                }
            }
            nodes.removeIf(node -> node.name == null || !connectedNodes.contains(node.name));
        }
        private void recalculateLinkLengths() {
            if (links.isEmpty() || nodes.isEmpty()) {
                return;
            }

            Map<String, NodeData> nodeMap = nodes.stream()
                    .filter(n -> n.name != null)
                    .collect(Collectors.toMap(n -> n.name, n -> n, (a, b) -> a));

            for (LinkData link : links) {
                NodeData from = nodeMap.get(link.fromNode);
                NodeData to = nodeMap.get(link.toNode);
                if (from == null || to == null) {
                    continue;
                }

                double dx = to.x - from.x;
                double dy = to.y - from.y;
                double dz = to.elevation - from.elevation;

                double horizontal = Math.hypot(dx, dy);
                double length3d = Math.hypot(horizontal, dz);

                if (!Double.isNaN(length3d) && !Double.isInfinite(length3d)) {
                    link.length = length3d;
                }
            }
        }

        private void ensureNodeTypes() {
            for (NodeData node : nodes) {
                if (node.nodeType == null || node.nodeType.trim().isEmpty()) {
                    node.nodeType = "JUNCTION";
                }
            }
        }

        private void calculateDepthsFromDem() {
            try {
                ArcGridReader reader = new ArcGridReader(new File(demPath));
                demCoverage = reader.read(null);
                reader.dispose();
                
                Set<String> depthAllowedShpTypes = new HashSet<>();
                for (Map.Entry<String, String> entry : nodeTypeMapping.entrySet()) {
                    String swmmType = entry.getValue();
                    if ("JUNCTION".equalsIgnoreCase(swmmType) || "INLET".equalsIgnoreCase(swmmType)) {
                        depthAllowedShpTypes.add(entry.getKey().toLowerCase());
                    }
                }
                depthAllowedShpTypes.add("junction");
                depthAllowedShpTypes.add("inlet");
                
                for (NodeData node : nodes) {
                    if (node.maxDepth > 0) {
                        continue;
                    }
                    String nodeTypeStr = node.nodeType != null ? node.nodeType.toLowerCase() : "";
                    if (depthAllowedShpTypes.contains(nodeTypeStr)) {
                        double groundElev = getElevationFromDem(node.x, node.y);
                        if (!Double.isNaN(groundElev)) {
                            node.maxDepth = Math.max(groundElev - node.elevation, 1.5);
                        } else {
                            node.maxDepth = 1.5;
                        }
                    } else {
                        node.maxDepth = Math.max(node.maxDepth, 0.0);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("DEM读取失败: {}", e.getMessage());
            }
        }

        private double getElevationFromDem(double x, double y) {
            if (demCoverage == null) {
                return Double.NaN;
            }
            try {
                double[] result = new double[1];
                demCoverage.evaluate(new Point2D.Double(x, y), result);
                return result[0];
            } catch (Exception e) {
                return Double.NaN;
            }
        }

        private void writeInpFile(String outputPath) throws IOException {
            StringBuilder content = new StringBuilder();
            content.append(createHeader());
            content.append(createOptions());
            content.append(processJunctions());
            content.append(processOutfalls());

            List<NodeData> inflowNodes = getInflowNodes();
            if (inflowNodes.isEmpty()) {
                List<String> expectedTypes = nodeTypeMapping.entrySet().stream()
                    .filter(e -> "INLET".equalsIgnoreCase(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
                String hint = expectedTypes.isEmpty() ? 
                    "请在字段映射配置中将Shapefile的节点类型映射到'INLET'" :
                    "当前配置中以下类型会被识别为入流节点: " + String.join(", ", expectedTypes);
                throw new IllegalStateException("未找到入流节点: Shapefile中必须包含映射到'INLET'类型的节点用于接收降雨输入。" + hint);
            }
            
            content.append(processRaingages());
            content.append(processTimeseries(inflowNodes));
            content.append(processInflows(inflowNodes));

            content.append(processConduits());
            content.append(processXsections());
            content.append(processCoordinates());

            Files.write(Paths.get(outputPath), content.toString().getBytes(StandardCharsets.UTF_8));
        }

        private String createHeader() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            return "[TITLE]\n" +
                    ";;Project Title/Notes\n" +
                    "SWMM Model Generated by Java\n" +
                    "日期: " + sdf.format(new Date()) + "\n\n";
        }

        private String createOptions() {
            int duration = simulationDuration != null ? simulationDuration : 180;
            int totalMinutes = duration;
            int days = totalMinutes / (24 * 60);
            int remainingMinutes = totalMinutes % (24 * 60);
            int hours = remainingMinutes / 60;
            int minutes = remainingMinutes % 60;
            
            String endDate = String.format("01/%02d/2020", days + 1);
            String endTime = String.format("%02d:%02d:00", hours, minutes);
            
            return "[OPTIONS]\n" +
                    ";;Option             Value\n" +
                    "FLOW_UNITS           CMS\n" +
                    "INFILTRATION         HORTON\n" +
                    "FLOW_ROUTING         DYNWAVE\n" +
                    "LINK_OFFSETS         DEPTH\n" +
                    "MIN_SLOPE            0.0001\n" +
                    "START_DATE           01/01/2020\n" +
                    "START_TIME           00:00:00\n" +
                    "END_DATE             " + endDate + "\n" +
                    "END_TIME             " + endTime + "\n" +
                    "REPORT_STEP          00:05:00\n" +
                    "WET_STEP             00:05:00\n" +
                    "ROUTING_STEP         0:01:00\n\n" +
                    "[REPORT]\n" +
                    ";;Reporting Options\n" +
                    "SUBCATCHMENTS ALL\n" +
                    "NODES ALL\n" +
                    "LINKS ALL\n\n";
        }

        private String processJunctions() {
            StringBuilder sb = new StringBuilder();
            sb.append("[JUNCTIONS]\n");
            sb.append(";;Name           Elevation  MaxDepth   InitDepth  SurDepth   Aponded   \n");
            sb.append(";;-------------- ---------- ---------- ---------- ---------- ----------\n");

            for (NodeData node : nodes) {
                String mappedType = nodeTypeMapping.getOrDefault(node.nodeType, "JUNCTION");
                if (!"OUTFALL".equals(mappedType)) {
                    sb.append(String.format(Locale.US, "%-16s %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f\n",
                            node.name, node.elevation, node.maxDepth, 0.0, 0.0, 0.0));
                }
            }
            sb.append("\n");
            return sb.toString();
        }

        private String processOutfalls() {
            StringBuilder sb = new StringBuilder();
            sb.append("[OUTFALLS]\n");
            sb.append(";;Name           Elevation  Type       Stage Data       Gated    Route To        \n");
            sb.append(";;-------------- ---------- ---------- ---------------- -------- ----------------\n");

            for (NodeData node : nodes) {
                String mappedType = nodeTypeMapping.getOrDefault(node.nodeType, "");
                if ("OUTFALL".equals(mappedType)) {
                    sb.append(String.format(Locale.US, "%-16s %-10.2f %-10s %-16s %-8s %-16s\n",
                            node.name, node.elevation, "FREE", "", "NO", ""));
                }
            }
            sb.append("\n");
            return sb.toString();
        }

        private List<NodeData> getInflowNodes() {
            Set<String> inletTypes = new HashSet<>();
            for (Map.Entry<String, String> entry : nodeTypeMapping.entrySet()) {
                if ("INLET".equalsIgnoreCase(entry.getValue())) {
                    inletTypes.add(entry.getKey().toLowerCase());
                }
            }
            inletTypes.add("inlet");
            
            return nodes.stream()
                    .filter(n -> {
                        String nodeTypeStr = String.valueOf(n.nodeType).toLowerCase();
                        return inletTypes.contains(nodeTypeStr);
                    })
                    .collect(Collectors.toList());
        }

        private String processRaingages() {
            return "[RAINGAGES]\n" +
                    ";;Name           Format    Interval SCF      Source    \n" +
                    ";;-------------- --------- ------ ------ ----------\n" +
                    "RG1              INTENSITY 1:00     1.0      TIMESERIES TS1\n\n";
        }

        private String processTimeseries(List<NodeData> inflowNodes) {
            StringBuilder sb = new StringBuilder();
            sb.append("[TIMESERIES]\n");
            sb.append(";;Name           Date       Time       Value     \n");
            sb.append(";;-------------- ---------- ---------- ----------\n");

            for (int i = 0; i < inflowNodes.size(); i++) {
                String tsName = String.valueOf(i);
                sb.append(String.format(Locale.US, "%-16s %-10s %-10s %-10s\n", tsName, "0:00", "0", ""));
                sb.append(String.format(Locale.US, "%-16s %-10s %-10s %-10s\n", tsName, "23:00", "0", ""));
                sb.append(";\n");
            }

            if (rainfallCsvPath != null && Files.exists(Paths.get(rainfallCsvPath))) {
                try (BufferedReader reader = Files.newBufferedReader(Paths.get(rainfallCsvPath), StandardCharsets.UTF_8)) {
                    String line;
                    boolean headerSkipped = false;
                    while ((line = reader.readLine()) != null) {
                        if (!headerSkipped) {
                            headerSkipped = true;
                            continue;
                        }
                        String[] parts = line.split(",");
                        if (parts.length >= 2) {
                            double seconds = Double.parseDouble(parts[0].trim());
                            double rainfall = Double.parseDouble(parts[1].trim());
                            int minutes = (int) (seconds / 60);
                            int hours = minutes / 60;
                            int mins = minutes % 60;
                            sb.append(String.format(Locale.US, "TS1              %02d:%02d      %.2f\n", hours, mins, rainfall));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("读取降雨CSV失败: {}", e.getMessage());
                }
            }
            sb.append("\n");
            return sb.toString();
        }

        private String processInflows(List<NodeData> inflowNodes) {
            StringBuilder sb = new StringBuilder();
            sb.append("[INFLOWS]\n");
            sb.append(";;Node           Constituent      Time Series      Type     Mfactor  Sfactor  Baseline Pattern\n");
            sb.append(";;-------------- ---------------- ---------------- -------- -------- -------- -------- --------\n");

            for (int i = 0; i < inflowNodes.size(); i++) {
                NodeData node = inflowNodes.get(i);
                String tsName = String.valueOf(i);
                sb.append(String.format(Locale.US, "%-16s %-16s %-16s %-8s %-8.2f %-8.2f %-8.2f %-8s\n",
                        node.name, "FLOW", tsName, "FLOW", 1.0, 1.0, 0.0, ""));
            }
            sb.append("\n");
            return sb.toString();
        }

        private String processConduits() {
            StringBuilder sb = new StringBuilder();
            sb.append("[CONDUITS]\n");
            sb.append(";;Name           From Node        To Node          Length     Roughness  InOffset   OutOffset  InitFlow   MaxFlow   \n");
            sb.append(";;-------------- ---------------- ---------------- ---------- ---------- ---------- ---------- ---------- ----------\n");

            for (LinkData link : links) {
                double roughness = link.roughness > 0 ? link.roughness : getMaterialRoughness(link.material);
                sb.append(String.format(Locale.US, "%-16s %-16s %-16s %-10.2f %-10.4f %-10.2f %-10.2f %-10.2f %-10.2f\n",
                        link.name, link.fromNode, link.toNode, link.length, roughness, 0.0, 0.0, 0.0, 0.0));
            }
            sb.append("\n");
            return sb.toString();
        }

        private double getMaterialRoughness(String material) {
            if (material == null) {
                return 0.013;
            }
            Map<String, Double> materialMap = new HashMap<>();
            materialMap.put("混凝土", 0.013);
            materialMap.put("PVC", 0.009);
            materialMap.put("钢", 0.012);
            return materialMap.getOrDefault(material, 0.013);
        }

        private String processXsections() {
            StringBuilder sb = new StringBuilder();
            sb.append("[XSECTIONS]\n");
            sb.append(";;Link           Shape        Geom1            Geom2      Geom3      Geom4      Barrels    Culvert   \n");
            sb.append(";;-------------- ------------ ---------------- ---------- ---------- ---------- ---------- ----------\n");

            for (LinkData link : links) {
                String shape = shapeTypeMapping.getOrDefault(link.shape, "CIRCULAR");
                sb.append(String.format(Locale.US, "%-16s %-12s %-16.4f %-10.4f %-10.4f %-10.4f %-10d %-10s\n",
                        link.name, shape, link.geom1, link.geom2, 0.0, 0.0, 1, ""));
            }
            sb.append("\n");
            return sb.toString();
        }

        private String processCoordinates() {
            StringBuilder sb = new StringBuilder();
            sb.append("[COORDINATES]\n");
            sb.append(";;Node           X-Coord            Y-Coord           \n");
            sb.append(";;-------------- ------------------ ------------------\n");

            for (NodeData node : nodes) {
                sb.append(String.format(Locale.US, "%-16s %-18.6f %-18.6f\n",
                        node.name, node.x, node.y));
            }
            sb.append("\n");
            return sb.toString();
        }

        private String getStringAttribute(SimpleFeature feature, String attributeName) {
            if (attributeName == null) {
                return "";
            }
            try {
                Object value = feature.getAttribute(attributeName);
                return value != null ? value.toString() : "";
            } catch (Exception e) {
                return "";
            }
        }

        private double getDoubleAttribute(SimpleFeature feature, String attributeName, double defaultValue) {
            if (attributeName == null) {
                return defaultValue;
            }
            try {
                Object value = feature.getAttribute(attributeName);
                if (value == null) {
                    return defaultValue;
                }
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                }
                return Double.parseDouble(value.toString());
            } catch (Exception e) {
                return defaultValue;
            }
        }

        private static class NodeData {
            String name;
            String originalName;
            double elevation;
            String nodeType;
            double maxDepth;
            double x;
            double y;
        }

        private static class LinkData {
            String name;
            String fromNode;
            String toNode;
            double length;
            String material;
            double roughness;
            String shape;
            double geom1;
            double geom2;
        }
    }
    /**
     * SWW original mesh interpolation index.
     */
    private static class MeshInterpolationIndex {
        final STRtree tree;
        final String source;
        final int triangleCount;
        final int skippedCount;

        MeshInterpolationIndex(STRtree tree, String source, int triangleCount, int skippedCount) {
            this.tree = tree;
            this.source = source == null ? "unknown" : source;
            this.triangleCount = triangleCount;
            this.skippedCount = skippedCount;
        }

        static MeshInterpolationIndex empty(String source) {
            return new MeshInterpolationIndex(null, source, 0, 0);
        }

        boolean hasTriangles() {
            return tree != null && triangleCount > 0;
        }

        List<?> query(double x, double y) {
            if (!hasTriangles()) {
                return Collections.emptyList();
            }
            return tree.query(new Envelope(x, x, y, y));
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("source", source);
            map.put("triangle_count", triangleCount);
            map.put("skipped_count", skippedCount);
            map.put("uses_original_mesh", hasTriangles());
            return map;
        }
    }

    private static class MeshTriangle {
        final int p0;
        final int p1;
        final int p2;
        final Coordinate a;
        final Coordinate b;
        final Coordinate c;
        final Envelope envelope;

        MeshTriangle(int p0, int p1, int p2, Coordinate a, Coordinate b, Coordinate c) {
            this.p0 = p0;
            this.p1 = p1;
            this.p2 = p2;
            this.a = a;
            this.b = b;
            this.c = c;
            this.envelope = new Envelope(a.x, a.x, a.y, a.y);
            this.envelope.expandToInclude(b);
            this.envelope.expandToInclude(c);
        }
    }

    /**
     * Global maximum values for frame encoding.
     */
    private static class GlobalMaxValues {
        double maxDepth = 0;
        double maxXMom = 0;
        double maxYMom = 0;
    }
    private static class FrameStats {
        double maxDepth;
        double meanDepth;
        double maxAbsXMom;
        double maxAbsYMom;
        int validCount;
        int pixelCount;

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("max_depth", maxDepth);
            map.put("mean_depth", meanDepth);
            map.put("max_abs_xmomentum", maxAbsXMom);
            map.put("max_abs_ymomentum", maxAbsYMom);
            map.put("valid_pixels", validCount);
            map.put("total_pixels", pixelCount);
            return map;
        }
    }
    private static class GridDataResult {
        double[][] depth;
        double[][] xMomentum;
        double[][] yMomentum;
        boolean[][] validMask;
    }
    private static class InterpolationResult {
        double depth = 0;
        double xMom = 0;
        double yMom = 0;
        boolean valid = false;
    }
}
