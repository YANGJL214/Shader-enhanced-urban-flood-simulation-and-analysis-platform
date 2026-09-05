package cn.com.medoxcq.module.warning.module.simulation.service.impl;

import cn.com.medoxcq.common.pojo.vo.CommonPageVO;
import cn.com.medoxcq.module.warning.module.simulation.entity.ModelSimulationHistory;
import cn.com.medoxcq.module.warning.module.simulation.enums.ModelNameEnum;
import cn.com.medoxcq.module.warning.module.simulation.enums.SimulationStatusEnum;
import cn.com.medoxcq.module.warning.module.simulation.mapper.ModelSimulationHistoryMapper;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.ModelSimulationHistoryQueryBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.bo.ModelSimulationHistorySaveBO;
import cn.com.medoxcq.module.warning.module.simulation.pojo.vo.ModelSimulationHistoryVO;
import cn.com.medoxcq.module.warning.module.simulation.service.ModelSimulationHistoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModelSimulationHistoryServiceImpl implements ModelSimulationHistoryService {

    private final ModelSimulationHistoryMapper mapper;

    @Override
    public CommonPageVO<ModelSimulationHistoryVO> page(ModelSimulationHistoryQueryBO bo) {
        LambdaQueryWrapper<ModelSimulationHistory> query = new LambdaQueryWrapper<>();
        query.like(bo.getSimulationName() != null && !bo.getSimulationName().trim().isEmpty(),
                        ModelSimulationHistory::getSimulationName, bo.getSimulationName())
                .eq(bo.getModelName() != null, ModelSimulationHistory::getModelName, bo.getModelName())
                .eq(bo.getState() != null, ModelSimulationHistory::getState, bo.getState())
                .eq(bo.getSaveData() != null, ModelSimulationHistory::getSaveData, bo.getSaveData())
                .ge(Boolean.TRUE.equals(bo.getToday()), ModelSimulationHistory::getCreateTime,
                        LocalDate.now().atStartOfDay())
                .orderByDesc(ModelSimulationHistory::getCreateTime);
        List<ModelSimulationHistoryVO> records = mapper.selectList(query).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        long current = bo.getCurrent() == null ? 1L : bo.getCurrent();
        long size = bo.getSize() == null ? 10L : bo.getSize();
        return CommonPageVO.build(records, current, size);
    }

    @Override
    @Transactional
    public String save(ModelSimulationHistorySaveBO bo) {
        ModelSimulationHistory entity = new ModelSimulationHistory();
        entity.setId(bo.getId() == null || bo.getId().trim().isEmpty()
                ? UUID.randomUUID().toString().replace("-", "") : bo.getId());
        apply(bo, entity);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setCreateBy("standalone");
        entity.setUpdateBy("standalone");
        entity.setDelFlag("0");
        mapper.insert(entity);
        return entity.getId();
    }

    @Override
    @Transactional
    public void update(ModelSimulationHistorySaveBO bo) {
        if (bo.getId() == null) return;
        ModelSimulationHistory entity = mapper.selectById(bo.getId());
        if (entity == null) return;
        apply(bo, entity);
        entity.setUpdateTime(LocalDateTime.now());
        entity.setUpdateBy("standalone");
        mapper.updateById(entity);
    }

    @Override
    public ModelSimulationHistoryVO detail(String id) {
        ModelSimulationHistory entity = mapper.selectById(id);
        return entity == null ? null : toVO(entity);
    }

    @Override
    public List<ModelSimulationHistory> queryRunningModel(ModelSimulationHistoryQueryBO bo) {
        if (bo == null) return Collections.emptyList();
        return mapper.selectList(new LambdaQueryWrapper<ModelSimulationHistory>()
                .eq(bo.getModelName() != null, ModelSimulationHistory::getModelName, bo.getModelName())
                .eq(ModelSimulationHistory::getState, SimulationStatusEnum.RUNNING)
                .ge(Boolean.TRUE.equals(bo.getToday()), ModelSimulationHistory::getCreateTime,
                        LocalDate.now().atStartOfDay()));
    }

    @Override
    public void historyV2(String id, String sessionId) {
    }

    private void apply(ModelSimulationHistorySaveBO bo, ModelSimulationHistory entity) {
        if (bo.getSimulationName() != null) entity.setSimulationName(bo.getSimulationName());
        if (bo.getModelName() != null) entity.setModelName(enumByCode(ModelNameEnum.values(), bo.getModelName()));
        if (bo.getState() != null) entity.setState(enumByCode(SimulationStatusEnum.values(), bo.getState()));
        if (bo.getSimulationParams() != null) entity.setSimulationParams(bo.getSimulationParams());
        if (bo.getProcessId() != null) entity.setProcessId(bo.getProcessId());
        if (bo.getSaveData() != null) entity.setSaveData(bo.getSaveData());
        if (bo.getWorkspacePath() != null) entity.setWorkspacePath(bo.getWorkspacePath());
    }

    private <E extends Enum<E> & cn.com.medoxcq.common.enums.BaseEnum> E enumByCode(E[] values, Integer code) {
        return Arrays.stream(values)
                .filter(value -> value.getCode().equals(code))
                .findFirst()
                .orElse(null);
    }

    private ModelSimulationHistoryVO toVO(ModelSimulationHistory entity) {
        ModelSimulationHistoryVO vo = new ModelSimulationHistoryVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
