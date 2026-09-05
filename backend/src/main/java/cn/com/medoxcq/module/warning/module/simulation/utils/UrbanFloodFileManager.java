package cn.com.medoxcq.module.warning.module.simulation.utils;

import cn.com.medoxcq.module.warning.config.SimulationConfiguration;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.UrbanFloodSaveBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 *
 * @author system
 * @since 2025/9/19
 */
@Slf4j
@Component
public class UrbanFloodFileManager {

    @Autowired
    private SimulationConfiguration simulationProperties;
    public ProjectWorkspace createProjectWorkspace(String projectName, UrbanFloodSaveBO bo,
                                                   MultipartFile[] nodeShpFiles, MultipartFile[] linkShpFiles,
                                                   MultipartFile ascFile, MultipartFile prjFile,
                                                   MultipartFile csvFile, MultipartFile landuseFile) throws Exception {
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String projectDirName = safeDirectoryName(projectName) + "_" + timestamp;
        String baseWorkspace = simulationProperties.getUrbanFloodWorkspace();
        Path projectDir = Paths.get(baseWorkspace, projectDirName);
        
        Files.createDirectories(projectDir);
//        Path inputDir = projectDir.resolve("input");
//        Path outputDir = projectDir.resolve("output");
//        Path configDir = projectDir.resolve("config");
//        Files.createDirectories(inputDir);
//        Files.createDirectories(outputDir);
//        Files.createDirectories(configDir);
        
        ProjectWorkspace workspace = new ProjectWorkspace();
        workspace.setProjectDir(projectDir.toString());
        workspace.setInputDir(projectDir.toString());
        workspace.setOutputDir(projectDir.toString());
        workspace.setConfigDir(projectDir.toString());
        
        ShapefileResource nodeShpResource = saveShapefileSet(nodeShpFiles, projectDir, "node_shp");
        ShapefileResource linkShpResource = saveShapefileSet(linkShpFiles, projectDir, "link_shp");
        String ascPath = saveFile(ascFile, projectDir, "terrain.asc");
        String prjPath = saveFile(prjFile, projectDir, "terrain.prj");
        String csvPath = saveFile(csvFile, projectDir, "rainfall.csv");
        Objects.requireNonNull(landuseFile, "土地利用栅格不能为空");
        String landusePath = saveFile(landuseFile, projectDir, normalizeLanduseFileName(landuseFile));
        
        String generatedInpPath = projectDir.resolve("network.inp").toString();
        workspace.setInpFilePath(generatedInpPath);
        workspace.setAscFilePath(ascPath);
        workspace.setPrjFilePath(prjPath);
        workspace.setCsvFilePath(csvPath);
        workspace.setLanduseFilePath(landusePath);
        workspace.setNodeShpPath(nodeShpResource.getShpPath());
        workspace.setNodeShpDir(nodeShpResource.getWorkingDir());
        workspace.setLinkShpPath(linkShpResource.getShpPath());
        workspace.setLinkShpDir(linkShpResource.getWorkingDir());
        
        log.info("项目工作空间创建完成: {}", projectDir);
        return workspace;
    }
    private static String normalizeLanduseFileName(MultipartFile landuseFile) {
        String original = landuseFile.getOriginalFilename();
        if (original == null || !original.contains(".")) {
            return "landuse.asc";
        }
        String ext = original.substring(original.lastIndexOf('.')).toLowerCase();
        if (!ext.equals(".tif") && !ext.equals(".tiff") && !ext.equals(".asc")) {
            ext = ".asc";
        }
        return "landuse" + ext;
    }

    private static String safeDirectoryName(String value) {
        String safe = value == null ? "" : value.trim()
                .replaceAll("[^\\p{L}\\p{N}._-]", "_")
                .replaceAll("\\.{2,}", "_");
        if (safe.isEmpty() || ".".equals(safe)) {
            safe = "simulation";
        }
        return safe.length() > 48 ? safe.substring(0, 48) : safe;
    }

    @Autowired
    private cn.com.medoxcq.module.warning.module.simulation.mapper.FloodRegionMapper floodRegionMapper;
    public cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion getRegionById(String regionId) {
        return floodRegionMapper.selectById(regionId);
    }
    public ProjectWorkspace createSimulationWorkspace(String simulationName,
                                                     cn.com.medoxcq.module.warning.module.simulation.entity.FloodRegion region,
                                                     MultipartFile csvFile) throws Exception {
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String projectDirName = safeDirectoryName(simulationName) + "_" + timestamp;
        String baseWorkspace = simulationProperties.getUrbanFloodWorkspace();
        Path projectDir = Paths.get(baseWorkspace, projectDirName);
        
        Files.createDirectories(projectDir);
        
        ProjectWorkspace workspace = new ProjectWorkspace();
        workspace.setProjectDir(projectDir.toString());
        workspace.setInputDir(projectDir.toString());
        workspace.setOutputDir(projectDir.toString());
        workspace.setConfigDir(projectDir.toString());
        
        String csvPath = saveFile(csvFile, projectDir, "rainfall.csv");
        workspace.setCsvFilePath(csvPath);
        
        workspace.setNodeShpPath(region.getNodeShpPath());
        workspace.setLinkShpPath(region.getLinkShpPath());
        workspace.setAscFilePath(region.getDemFilePath());
        workspace.setLanduseFilePath(region.getLanduseFilePath());
        
        String generatedInpPath = projectDir.resolve("network.inp").toString();
        workspace.setInpFilePath(generatedInpPath);
        
        log.info("模拟工作空间创建完成: {}", projectDir);
        return workspace;
    }
    public Process executeSimulation(String batFilePath, OutputCallback outputCallback) throws Exception {
        log.info("开始执行模型仿真: {}", batFilePath);
        
        ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/c", batFilePath);
        processBuilder.directory(new File(Paths.get(batFilePath).getParent().toString()));
        
        Map<String, String> env = processBuilder.environment();
        env.put("PYTHONUNBUFFERED", "1");
        
        processBuilder.redirectErrorStream(true);
        
        Process process = processBuilder.start();
        
        startOutputMonitor(process, outputCallback);
        
        return process;
    }
    @FunctionalInterface
    public interface OutputCallback {
        void onOutput(String outputLine);
    }
    public interface ExtendedOutputCallback extends OutputCallback {
        default void onSimulationStart() {}
        default void onSimulationSuccess() {}
        default void onSimulationError(String errorMessage) {}
    }
    public ModelRunStatus checkSimulationStatus(Process process, String outputDir) {
        return checkSimulationStatus(process, outputDir, false);
    }
    public ModelRunStatus checkSimulationStatus(Process process, String outputDir, boolean hasSuccessMarker) {
        ModelRunStatus status = new ModelRunStatus();
        status.setHasSuccessMarker(hasSuccessMarker);
        
        if (process == null) {
            status.setStatus("ERROR");
            status.setMessage("进程未启动");
            return status;
        }
        
        if (process.isAlive()) {
            status.setStatus("RUNNING");
            status.setMessage("模型正在运行");
        } else {
            int exitCode = process.exitValue();
            log.info("进程已结束，退出码: {}, 成功标记: {}", exitCode, hasSuccessMarker);
            
            switch (exitCode) {
                case 0:
                    if (hasSuccessMarker) {
                        status.setStatus("COMPLETED");
                        status.setMessage("模型运行成功完成（检测到成功标记）");
                        log.info("模型成功完成（通过成功标记）");
                    } else {
                        boolean hasOutputFiles = checkOutputFiles(outputDir);
                        log.info("检查输出文件: outputDir={}, hasFiles={}", outputDir, hasOutputFiles);
                        if (hasOutputFiles) {
                            status.setStatus("COMPLETED");
                            status.setMessage("模型运行成功完成（检测到输出文件）");
                            log.info("模型成功完成（通过输出文件）");
                        } else {
                            status.setStatus("ERROR");
                            status.setMessage("模型退出正常但未找到输出文件或成功标记");
                            log.warn("模型退出但未找到输出文件或成功标记");
                        }
                    }
                    break;
                case 1:
                    status.setStatus("ERROR");
                    status.setMessage("模型参数错误（退出码: 1）");
                    break;
                case 2:
                    status.setStatus("ERROR");
                    status.setMessage("模型计算错误（退出码: 2）");
                    break;
                case 3:
                    status.setStatus("ERROR");
                    status.setMessage("模型发生异常（退出码: 3）");
                    break;
                case 4:
                    status.setStatus("ERROR");
                    status.setMessage("模型发生未知异常（退出码: 4）");
                    break;
                case 5:
                    status.setStatus("ERROR");
                    status.setMessage("模型文件读取错误（退出码: 5）");
                    break;
                case 6:
                    status.setStatus("ERROR");
                    status.setMessage("模型文件写入错误（退出码: 6）");
                    break;
                default:
                    status.setStatus("ERROR");
                    status.setMessage("模型运行失败（退出码: " + exitCode + "）");
                    break;
            }
        }
        
        return status;
    }
    public String getYamlTemplatePath() {
        return Paths.get(simulationProperties.getUrbanFloodTemplatePath(), "urban_flood_template.yaml").toString();
    }
    public String getBatTemplatePath() {
        return Paths.get(simulationProperties.getUrbanFloodTemplatePath(), "startup_template.bat").toString();
    }
    public SimulationConfiguration getSimulationProperties() {
        return simulationProperties;
    }
    

    
    private static String saveFile(MultipartFile file, Path targetDir, String fileName) throws Exception {
        Path filePath = targetDir.resolve(fileName);
        Files.copy(file.getInputStream(), filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.info("文件保存成功: {}", filePath);
        return filePath.toString();
    }
    private static ShapefileResource saveShapefileSet(MultipartFile[] files, Path projectDir, String subDir) throws Exception {
        Path shpDir = projectDir.resolve(subDir);
        Files.createDirectories(shpDir);

        Path shpFilePath = null;
        boolean hasZipFile = false;

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String originalName = file.getOriginalFilename();
            if (originalName != null && originalName.toLowerCase().endsWith(".zip")) {
                Path zipPath = shpDir.resolve(originalName);
                Files.copy(file.getInputStream(), zipPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                unzip(zipPath, shpDir);
                hasZipFile = true;
                log.info("ZIP文件已解压: {}", zipPath);
                break;
            }
        }

        if (!hasZipFile) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String originalName = file.getOriginalFilename();
                if (originalName == null) {
                    continue;
                }

                String lower = originalName.toLowerCase();
                Path targetPath = shpDir.resolve(originalName);
                
                Files.copy(file.getInputStream(), targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("Shapefile相关文件已保存: {}", targetPath);

                if (lower.endsWith(".shp")) {
                    shpFilePath = targetPath;
                }
            }
        }

        if (shpFilePath == null) {
            shpFilePath = findFileByExtension(shpDir, ".shp");
        }

        if (shpFilePath == null) {
            throw new IllegalArgumentException("未找到.shp文件在目录: " + shpDir);
        }

        String baseName = shpFilePath.getFileName().toString();
        baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        
        Path shxFile = shpDir.resolve(baseName + ".shx");
        Path dbfFile = shpDir.resolve(baseName + ".dbf");
        
        if (!Files.exists(shxFile)) {
            log.warn("警告: 未找到.shx文件: {}，可能导致Shapefile读取失败", shxFile);
        }
        if (!Files.exists(dbfFile)) {
            log.warn("警告: 未找到.dbf文件: {}，可能导致属性数据缺失", dbfFile);
        }

        log.info("Shapefile文件集处理完成: {}", shpFilePath);
        return new ShapefileResource(shpFilePath.toString(), shpDir.toString());
    }

    private static ShapefileResource saveShapefile(MultipartFile file, Path projectDir, String subDir) throws Exception {
        Path shpDir = projectDir.resolve(subDir);
        Files.createDirectories(shpDir);

        String originalName = file.getOriginalFilename();
        if (originalName == null) {
            throw new IllegalArgumentException("上传的Shapefile缺少文件名");
        }

        String lower = originalName.toLowerCase();
        if (lower.endsWith(".zip")) {
            Path zipPath = shpDir.resolve(originalName);
            Files.copy(file.getInputStream(), zipPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            unzip(zipPath, shpDir);
            Path shpPath = findFileByExtension(shpDir, ".shp");
            if (shpPath == null) {
                throw new IllegalArgumentException("ZIP包内未找到SHP文件: " + originalName);
            }
            return new ShapefileResource(shpPath.toString(), shpDir.toString());
        } else if (lower.endsWith(".shp")) {
            Path shpPath = shpDir.resolve(originalName);
            Files.copy(file.getInputStream(), shpPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new ShapefileResource(shpPath.toString(), shpDir.toString());
        }

        throw new IllegalArgumentException("不支持的Shapefile格式: " + originalName);
    }

    private static void unzip(Path zipPath, Path targetDir) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new FileInputStream(zipPath.toFile()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("ZIP条目路径非法: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream os = Files.newOutputStream(entryPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static Path findFileByExtension(Path dir, String extension) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(extension))
                    .findFirst()
                    .orElse(null);
        }
    }
    

    
    private static Map<String, String> createReplacementMap(ProjectWorkspace workspace, UrbanFloodSaveBO bo) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("${PROJECT_NAME}", bo.getProjectName());
        replacements.put("${SIMULATION_DURATION}", String.valueOf(bo.getSimulationDuration()));
        replacements.put("${MANNINGS_N}", String.valueOf(bo.getManningsN()));
        replacements.put("${SEDIMENT_CONCENTRATION}", String.valueOf(bo.getSedimentConcentration()));
        replacements.put("${VEGETATION_STEM_DIAMETER}", String.valueOf(bo.getVegetationStemDiameter()));
        replacements.put("${VEGETATION_STEM_SPACING}", String.valueOf(bo.getVegetationStemSpacing()));
        replacements.put("${INP_FILE_PATH}", workspace.getInpFilePath());
        replacements.put("${ASC_FILE_PATH}", workspace.getAscFilePath());
        replacements.put("${PRJ_FILE_PATH}", workspace.getPrjFilePath());
        replacements.put("${CSV_FILE_PATH}", workspace.getCsvFilePath());
        replacements.put("${OUTPUT_DIR}", workspace.getOutputDir());
        return replacements;
    }
    
    private static String replaceTemplateVariables(String template, Map<String, String> replacements) {
        String result = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
    

    private static void startOutputMonitor(Process process, OutputCallback outputCallback) {
        Thread outputMonitor = new Thread(() -> {
            try {
                InputStream inputStream = process.getInputStream();
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream, "GBK");
                StringBuilder lineBuffer = new StringBuilder();

                int ch;
                while ((ch = inputStreamReader.read()) != -1) {
                    if (ch == '\n' || ch == '\r') {
                        if (lineBuffer.length() > 0) {
                            String outputLine = lineBuffer.toString().trim();
                            if (!outputLine.isEmpty()) {
                                log.info("模型输出: {}", outputLine);
                                
                                if (outputCallback instanceof ExtendedOutputCallback) {
                                    ExtendedOutputCallback extCallback = (ExtendedOutputCallback) outputCallback;
                                    
                                    if (outputLine.contains("[SIMULATION_START]")) {
                                        extCallback.onSimulationStart();
                                    } else if (outputLine.contains("[SIMULATION_SUCCESS]")) {
                                        extCallback.onSimulationSuccess();
                                    } else if (outputLine.contains("[ERROR]") || outputLine.contains("[EXCEPTION]")) {
                                        extCallback.onSimulationError(outputLine);
                                    }
                                }
                                
                                if (outputCallback != null) {
                                    outputCallback.onOutput(outputLine);
                                }
                            }
                            lineBuffer.setLength(0);
                        }
                    } else {
                        lineBuffer.append((char) ch);
                    }
                }

                if (lineBuffer.length() > 0) {
                    String outputLine = lineBuffer.toString().trim();
                    if (!outputLine.isEmpty()) {
                        log.info("模型输出: {}", outputLine);
                        
                        if (outputCallback instanceof ExtendedOutputCallback) {
                            ExtendedOutputCallback extCallback = (ExtendedOutputCallback) outputCallback;
                            
                            if (outputLine.contains("[SIMULATION_SUCCESS]")) {
                                extCallback.onSimulationSuccess();
                            } else if (outputLine.contains("[ERROR]") || outputLine.contains("[EXCEPTION]")) {
                                extCallback.onSimulationError(outputLine);
                            }
                        }
                        
                        if (outputCallback != null) {
                            outputCallback.onOutput(outputLine);
                        }
                    }
                }

            } catch (Exception e) {
                log.error("监控模型输出时发生错误", e);
            }
        });
        outputMonitor.setDaemon(true);
        outputMonitor.setPriority(Thread.MAX_PRIORITY);
        outputMonitor.start();
    }
    
    private static boolean checkOutputFiles(String outputDir) {
        try {
            Path outputPath = Paths.get(outputDir);
            if (!Files.exists(outputPath)) {
                return false;
            }
            
            String[] expectedFiles = {".vtk", ".asc", ".csv", ".txt", ".sww", ".out"};
            try (java.util.stream.Stream<Path> files = Files.list(outputPath)) {
                return files.anyMatch(file -> {
                    String fileName = file.getFileName().toString().toLowerCase();
                    for (String ext : expectedFiles) {
                        if (fileName.endsWith(ext)) {
                            return true;
                        }
                    }
                    return false;
                });
            }
        } catch (Exception e) {
            log.error("检查输出文件时发生错误", e);
            return false;
        }
    }
    
    private static class ShapefileResource {
        private final String shpPath;
        private final String workingDir;

        ShapefileResource(String shpPath, String workingDir) {
            this.shpPath = shpPath;
            this.workingDir = workingDir;
        }

        public String getShpPath() {
            return shpPath;
        }

        public String getWorkingDir() {
            return workingDir;
        }
    }
    public static class ProjectWorkspace {
        private String projectDir;
        private String inputDir;
        private String outputDir;
        private String configDir;
        private String inpFilePath;
        private String ascFilePath;
        private String prjFilePath;
        private String csvFilePath;
        private String landuseFilePath;
        private String yamlConfigPath;
        private String batFilePath;
        private String nodeShpPath;
        private String nodeShpDir;
        private String linkShpPath;
        private String linkShpDir;
        
        public String getProjectDir() { return projectDir; }
        public void setProjectDir(String projectDir) { this.projectDir = projectDir; }
        public String getInputDir() { return inputDir; }
        public void setInputDir(String inputDir) { this.inputDir = inputDir; }
        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
        public String getConfigDir() { return configDir; }
        public void setConfigDir(String configDir) { this.configDir = configDir; }
        public String getInpFilePath() { return inpFilePath; }
        public void setInpFilePath(String inpFilePath) { this.inpFilePath = inpFilePath; }
        public String getAscFilePath() { return ascFilePath; }
        public void setAscFilePath(String ascFilePath) { this.ascFilePath = ascFilePath; }
        public String getPrjFilePath() { return prjFilePath; }
        public void setPrjFilePath(String prjFilePath) { this.prjFilePath = prjFilePath; }
        public String getCsvFilePath() { return csvFilePath; }
        public void setCsvFilePath(String csvFilePath) { this.csvFilePath = csvFilePath; }
        public String getLanduseFilePath() { return landuseFilePath; }
        public void setLanduseFilePath(String landuseFilePath) { this.landuseFilePath = landuseFilePath; }
        public String getYamlConfigPath() { return yamlConfigPath; }
        public void setYamlConfigPath(String yamlConfigPath) { this.yamlConfigPath = yamlConfigPath; }
        public String getBatFilePath() { return batFilePath; }
        public void setBatFilePath(String batFilePath) { this.batFilePath = batFilePath; }
        public String getNodeShpPath() { return nodeShpPath; }
        public void setNodeShpPath(String nodeShpPath) { this.nodeShpPath = nodeShpPath; }
        public String getNodeShpDir() { return nodeShpDir; }
        public void setNodeShpDir(String nodeShpDir) { this.nodeShpDir = nodeShpDir; }
        public String getLinkShpPath() { return linkShpPath; }
        public void setLinkShpPath(String linkShpPath) { this.linkShpPath = linkShpPath; }
        public String getLinkShpDir() { return linkShpDir; }
        public void setLinkShpDir(String linkShpDir) { this.linkShpDir = linkShpDir; }
    }
    public static class ModelRunStatus {
        private String status; // RUNNING, COMPLETED, ERROR
        private String message;
        private double progress;
        private boolean hasSuccessMarker;
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public double getProgress() { return progress; }
        public void setProgress(double progress) { this.progress = progress; }
        public boolean isHasSuccessMarker() { return hasSuccessMarker; }
        public void setHasSuccessMarker(boolean hasSuccessMarker) { this.hasSuccessMarker = hasSuccessMarker; }
        
        public boolean isCompleted() {
            return "COMPLETED".equals(status) || "ERROR".equals(status);
        }
        
        public boolean isSuccess() {
            return "COMPLETED".equals(status);
        }
    }
}
