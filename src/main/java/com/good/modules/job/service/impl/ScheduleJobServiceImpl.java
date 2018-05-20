package com.good.modules.job.service.impl;

import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.plugins.Page;
import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import com.good.common.Constant.JobStatus;
import com.good.common.util.ShiroUtils;
import com.good.common.util.job.ScheduleUtils;
import com.good.modules.job.mapper.ScheduleJobMapper;
import com.good.modules.job.model.ScheduleJob;
import com.good.modules.job.service.ScheduleJobService;
import com.good.modules.sys.model.User;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.quartz.CronTrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * @author cuiP
 * Created by JK on 2017/5/4.
 */
@Transactional(rollbackFor = Exception.class)
@Service
public class ScheduleJobServiceImpl extends ServiceImpl<ScheduleJobMapper, ScheduleJob> implements ScheduleJobService{

    @Autowired
    private SchedulerFactoryBean schedulerFactoryBean;
    @Autowired
    private ScheduleJobMapper scheduleJobMapper;

    @Override
    public void initScheduleJob() {
        List<ScheduleJob> scheduleJobList = scheduleJobMapper.findList();

        if (CollectionUtils.isEmpty(scheduleJobList)) {
            return;
        }

        for (ScheduleJob scheduleJob : scheduleJobList) {
            CronTrigger cronTrigger = ScheduleUtils.getCronTrigger(schedulerFactoryBean.getScheduler(), scheduleJob.getJobName(), scheduleJob.getJobGroup());

            //不存在，创建一个
            if (cronTrigger == null) {
                ScheduleUtils.createScheduleJob(schedulerFactoryBean.getScheduler(), scheduleJob);
            } else {
                //已存在，那么更新相应的定时设置
                ScheduleUtils.updateScheduleJob(schedulerFactoryBean.getScheduler(), scheduleJob);
            }
        }
    }


    @Override
    public Page<ScheduleJob> findPage(Integer pageNum, Integer pageSize, String jobName, String startTime, String endTime) {
        return this.selectPage(
                new Page<>(pageNum, pageSize),
                new EntityWrapper<ScheduleJob>()
                        .like(StringUtils.isNotBlank(jobName), "job_name", jobName)
                        .ge(StringUtils.isNotBlank(startTime), "create_time", startTime + "00:00:00")
                        .le(StringUtils.isNotBlank(endTime), "create_time", endTime + "23:59:59")
                        .orderBy("create_time", false)
        );
    }

    @Override
    public void saveScheduleJob(ScheduleJob scheduleJob) {
        scheduleJob.setStatus(JobStatus.NORMAL.getValue());
        //创建调度任务
        ScheduleUtils.createScheduleJob(schedulerFactoryBean.getScheduler(), scheduleJob);

        //保存到数据库
        User user = ShiroUtils.getUserEntity();

        scheduleJob.setId(null);
        scheduleJob.setStatus(1);
        scheduleJob.setCreateBy(user.getId());
        if(scheduleJob.getIsLocal()){
            scheduleJob.setRemoteUrl(null);
            scheduleJob.setRemoteRequestMethod(null);
        }else {
            scheduleJob.setBeanClass(null);
            scheduleJob.setMethodName(null);
            //默认只支持post
            scheduleJob.setRemoteRequestMethod("POST");
        }
        this.insert(scheduleJob);
    }

    @Override
    public void updateScheduleJob(ScheduleJob scheduleJob) {

        //根据ID获取修改前的任务记录
        ScheduleJob record = this.selectById(scheduleJob.getId());

        //参数赋值
        scheduleJob.setRemoteRequestMethod(record.getRemoteRequestMethod());
        scheduleJob.setStatus(record.getStatus());
        scheduleJob.setCreateBy(record.getCreateBy());
        scheduleJob.setModifyBy(record.getModifyBy());
        scheduleJob.setCreateTime(record.getCreateTime());
        scheduleJob.setModifyTime(record.getModifyTime());
        if(scheduleJob.getIsLocal()){
            scheduleJob.setIsLocal(true);
            scheduleJob.setBeanClass(scheduleJob.getBeanClass());
            scheduleJob.setMethodName(scheduleJob.getMethodName());
            scheduleJob.setRemoteUrl(null);
            scheduleJob.setRemoteRequestMethod(null);
        }else {
            scheduleJob.setIsLocal(false);
            scheduleJob.setRemoteUrl(scheduleJob.getRemoteUrl());
            //默认只支持post
            scheduleJob.setRemoteRequestMethod("POST");
            scheduleJob.setBeanClass(null);
            scheduleJob.setMethodName(null);
        }

        //因为Quartz只能更新cron表达式，当更改了cron表达式以外的属性时，执行的逻辑是：先删除旧的再创建新的。注:equals排除了cron属性
        if(!scheduleJob.equals(record)){
            //删除旧的任务
            ScheduleUtils.deleteScheduleJob(schedulerFactoryBean.getScheduler(), record.getJobName(), record.getJobGroup());
            //创建新的任务,保持原来任务的状态
            scheduleJob.setStatus(record.getStatus());
            ScheduleUtils.createScheduleJob(schedulerFactoryBean.getScheduler(), scheduleJob);
        }else {
            //当cron表达式和原来不一致才做更新
            if(!scheduleJob.getCron().equals(record.getCron())){
                //更新调度任务
                ScheduleUtils.updateScheduleJob(schedulerFactoryBean.getScheduler(), scheduleJob);
            }
        }

        //更新数据库
        User user = ShiroUtils.getUserEntity();
        scheduleJob.setModifyBy(user.getId());
        this.updateById(scheduleJob);
    }

    @Override
    public void deleteScheduleJob(Long jobId) {
        ScheduleJob scheduleJob = this.selectById(jobId);
        //删除运行的任务
        ScheduleUtils.deleteScheduleJob(schedulerFactoryBean.getScheduler(), scheduleJob.getJobName(), scheduleJob.getJobGroup());
        //删除数据
        super.deleteById(jobId);
    }

    @Override
    public void pauseJob(Long jobId) {
        ScheduleJob scheduleJob = this.selectById(jobId);
        //暂停正在运行的调度任务
        ScheduleUtils.pauseJob(schedulerFactoryBean.getScheduler(), scheduleJob.getJobName(), scheduleJob.getJobGroup());
        //更新数据库状态为 禁用 0
        ScheduleJob model = new ScheduleJob();
        model.setId(jobId);
        model.setStatus(0);
        this.updateById(model);
    }

    @Override
    public void resumeJob(Long jobId) {
        ScheduleJob scheduleJob = this.selectById(jobId);
        //恢复处于暂停中的调度任务
        ScheduleUtils.resumeJob(schedulerFactoryBean.getScheduler(), scheduleJob.getJobName(), scheduleJob.getJobGroup());
        //更新数据库状态 启用 1
        ScheduleJob model = new ScheduleJob();
        model.setId(jobId);
        model.setStatus(1);
        super.updateById(model);
    }

    @Override
    public void runOnce(Long jobId) {
        ScheduleJob scheduleJob = this.selectById(jobId);
        //运行一次
        ScheduleUtils.runOnce(schedulerFactoryBean.getScheduler(), scheduleJob.getJobName(), scheduleJob.getJobGroup());
    }

    @Transactional(readOnly = true)
    @Override
    public ScheduleJob findByJobNameAndJobGroup(String jobName, String jobGroup) {
        return this.selectOne(
                new EntityWrapper<ScheduleJob>()
                        .eq(StringUtils.isNotBlank(jobName), "job_name", jobName)
                        .eq(StringUtils.isNotBlank(jobGroup), "job_group", jobGroup)
        );
    }
}
