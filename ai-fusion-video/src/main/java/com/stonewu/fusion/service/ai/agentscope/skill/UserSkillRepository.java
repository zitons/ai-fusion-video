package com.stonewu.fusion.service.ai.agentscope.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;

import java.util.List;

/**
 * 只读 Skill 仓库：把当前用户 workspace 的 Skill 暴露给 agentscope 的 skill 工具
 * （load_skill_through_path 等）。用户 Skill 存储在聊天助手的 namespace 下，
 * 而各 agent 的 workspace 文件系统只映射自己的 namespace，导致其他 agent 的
 * skill catalog 为空、工具枚举为空；通过本仓库在构建 agent 时按用户挂载即可修复。
 */
public final class UserSkillRepository implements AgentSkillRepository {

    private final AgentUserSkillService userSkillService;
    private final long userId;
    private final String source;

    public UserSkillRepository(AgentUserSkillService userSkillService, long userId) {
        this.userSkillService = userSkillService;
        this.userId = userId;
        this.source = "workspace:user:" + userId;
    }

    @Override
    public AgentSkill getSkill(String name) {
        return getAllSkills().stream()
                .filter(skill -> name.equals(skill.getName()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<String> getAllSkillNames() {
        return getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return userSkillService.list(userId).stream()
                .map(skill -> AgentSkill.builder()
                        .name(skill.name())
                        .description(skill.description())
                        .skillContent(skill.content())
                        .resources(userSkillService.skillResources(userId, skill.name()))
                        .source(source)
                        .build())
                .toList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        return false;
    }

    @Override
    public boolean delete(String name) {
        return false;
    }

    @Override
    public boolean skillExists(String name) {
        return getSkill(name) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("workspace-user", source, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    @Override
    public void setWriteable(boolean writeable) {
        // read-only
    }

    @Override
    public boolean isWriteable() {
        return false;
    }
}
