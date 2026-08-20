package com.stonewu.fusion.service.ai.agentscope.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.config.AgentScopeV2Properties;
import com.stonewu.fusion.config.ai.AiAgentDefinition;
import com.stonewu.fusion.controller.ai.vo.AiChatReqVO;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.service.ai.AiAgentService;
import com.stonewu.fusion.service.ai.AiToolConfigService;
import com.stonewu.fusion.service.ai.ToolExecutor;
import com.stonewu.fusion.service.ai.agentscope.AgentScopeModelFactory;
import com.stonewu.fusion.service.ai.agentscope.context.ProjectContext;
import com.stonewu.fusion.service.ai.agentscope.mcp.AgentScopeMcpRegistry;
import com.stonewu.fusion.service.ai.agentscope.permission.ToolExecutionMode;
import com.stonewu.fusion.service.ai.agentscope.skill.AgentUserSkillService;
import com.stonewu.fusion.service.ai.agentscope.tool.AgentScopeToolSchema;
import com.stonewu.fusion.service.ai.run.kernel.AgentKernelSnapshotPayload;
import com.stonewu.fusion.service.ai.run.kernel.ToolManifestSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates immutable root and platform-child Harness kernel specifications. */
@Component
public final class AgentKernelSpecFactory {

    public static final String DEFAULT_AGENT_KEY = "ai_assistant_agent";
    public static final String TOOL_WHITELIST_VERSION = "afv-tools-v2";
    public static final String OWNER_USER_ID_VARIABLE = "agentWorkspaceUserId";
    public static final String MCP_CONFIG_FINGERPRINT_VARIABLE = "agentMcpConfigFingerprint";
    public static final String TOOL_EXECUTION_MODE_VARIABLE = "agentToolExecutionMode";
    /** 子 Agent 系统提示词中注入用户 Skill 的总长度上限（与聊天主动引用一致）。 */
    private static final int MAX_INJECTED_SKILL_CHARS = 64 * 1024;
    private final AiAgentService agentService;
    private final AiToolConfigService toolConfigService;
    private final AgentScopeModelFactory modelFactory;
    private final AgentScopeV2Properties properties;
    private final ObjectMapper objectMapper;
    private final AgentScopeMcpRegistry mcpRegistry;
    private final AgentUserSkillService userSkillService;

    @Autowired
    public AgentKernelSpecFactory(
            AiAgentService agentService,
            AiToolConfigService toolConfigService,
            AgentScopeModelFactory modelFactory,
            AgentScopeV2Properties properties,
            ObjectMapper objectMapper,
            AgentScopeMcpRegistry mcpRegistry,
            AgentUserSkillService userSkillService) {
        this.agentService = Objects.requireNonNull(agentService, "agentService must not be null");
        this.toolConfigService = Objects.requireNonNull(
                toolConfigService, "toolConfigService must not be null");
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.mcpRegistry = Objects.requireNonNull(mcpRegistry, "mcpRegistry must not be null");
        this.userSkillService = userSkillService;
    }

    public AgentKernelSpecFactory(
            AiAgentService agentService,
            AiToolConfigService toolConfigService,
            AgentScopeModelFactory modelFactory,
            AgentScopeV2Properties properties,
            ObjectMapper objectMapper) {
        this(
                agentService,
                toolConfigService,
                modelFactory,
                properties,
                objectMapper,
                new AgentScopeMcpRegistry(properties, objectMapper),
                null);
    }

    public AgentKernelSpec createRoot(AiChatReqVO request, AiModel model, String systemPrompt) {
        return createRoot(request, model, systemPrompt, null);
    }

    public AgentKernelSpec createRoot(
            AiChatReqVO request,
            AiModel model,
            String systemPrompt,
            Long ownerUserId) {
        Objects.requireNonNull(request, "request must not be null");
        String requestedType = normalize(request.getAgentType());
        String definitionKey = requestedType == null ? DEFAULT_AGENT_KEY : requestedType;
        AiAgentDefinition definition = requestedType == null
                ? null
                : agentService.getRequiredByType(requestedType);
        String agentName = definition == null
                ? DEFAULT_AGENT_KEY
                : requireText(definition.getName(), "agent.name");
        ToolSelection tools = selectTools(
                requestedType,
                request.getEnabledTools(),
                request.getEnabledMcpTools(),
                ownerUserId);
        Map<String, String> promptVariables = new LinkedHashMap<>(
                AgentPromptVariables.fromRequest(request));
        promptVariables.put(
                TOOL_EXECUTION_MODE_VARIABLE,
                ToolExecutionMode.parse(request.getToolExecutionMode()).name());
        if (ownerUserId != null) {
            promptVariables.put(OWNER_USER_ID_VARIABLE, String.valueOf(ownerUserId));
            String mcpFingerprint = mcpRegistry.userConfigurationFingerprint(ownerUserId);
            if (mcpFingerprint != null && !mcpFingerprint.isBlank()) {
                promptVariables.put(MCP_CONFIG_FINGERPRINT_VARIABLE, mcpFingerprint);
            }
        }
        return create(
                model,
                definitionKey,
                agentName,
                "AgentScope Harness kernel for " + agentName,
                systemPrompt,
                promptVariables,
                tools,
                modelFactory.modelConfigFingerprint(model));
    }

    public AgentKernelSpec createChild(
            AgentKernelSpec parent,
            AiAgentDefinition.SubAgentToolDef subAgent,
            ProjectContext project,
            Map<String, Object> input) {
        Objects.requireNonNull(parent, "parent must not be null");
        Objects.requireNonNull(subAgent, "subAgent must not be null");
        String childType = requireText(subAgent.getRefAgentType(), "subAgent.refAgentType");
        AiAgentDefinition definition = agentService.getRequiredByType(childType);
        String prompt = normalize(subAgent.getSystemPromptOverride());
        if (prompt == null) {
            prompt = requireText(definition.getSystemPrompt(), "subAgent.systemPrompt");
        }
        String instruction = normalize(subAgent.getInstructionOverride());
        if (instruction == null) {
            instruction = normalize(definition.getInstructionTemplate());
        }
        Map<String, String> variables = AgentPromptVariables.forChild(
                parent.promptVariables(), project, input);
        prompt = AgentPromptVariables.render(prompt, variables);
        if (instruction != null) {
            prompt = prompt + "\n\n" + AgentPromptVariables.render(instruction, variables);
        }
        ToolSelection tools = selectTools(childType, null, null, ownerUserId(parent));
        String agentName = normalize(subAgent.getToolName());
        if (agentName == null) {
            agentName = requireText(definition.getName(), "subAgent.name");
        }
        Long ownerId = ownerUserId(parent);
        if (ownerId != null) {
            prompt = appendUserSkills(prompt, ownerId);
        }
        return create(
                parent.model(),
                childType,
                agentName,
                requireText(subAgent.getDescription(), "subAgent.description"),
                prompt,
                variables,
                tools,
                parent.key().modelConfigFingerprint());
    }

    /**
     * 将当前用户的全部 Skill 注入子 Agent 系统提示词。
     * 平台 Skill 激活是显式引用制（enabledSkills），分镜等 pipeline 子 Agent 没有
     * 引用入口，因此这里把全部用户 Skill 直接拼入 prompt，让子 Agent 在相关任务中
     * 必须遵循对应 Skill 的正文要求；与任务无关的 Skill 由模型自行忽略。
     */
    private String appendUserSkills(String prompt, long userId) {
        if (userSkillService == null) {
            return prompt;
        }
        List<AgentUserSkillService.UserSkill> skills = userSkillService.list(userId);
        if (skills.isEmpty()) {
            return prompt;
        }
        StringBuilder block = new StringBuilder(
                "\n\n## 已注入的用户 Skills\n"
                        + "以下为当前用户的全部 Skill（正文已完整给出，无需再通过 load_skill_through_path 等工具加载）。"
                        + "若当前任务与某个 Skill 相关，该 Skill 的要求**优先于**本提示词中的通用规则，必须严格遵循；"
                        + "与本任务无关的 Skill 直接忽略。\n");
        int total = 0;
        for (AgentUserSkillService.UserSkill skill : skills) {
            String content = skill.content();
            if (total + content.length() > MAX_INJECTED_SKILL_CHARS) {
                break;
            }
            block.append("\n### Skill: ").append(skill.name())
                    .append("\n描述：").append(skill.description())
                    .append("\n\n").append(content).append('\n');
            total += content.length();
        }
        if (total == 0) {
            return prompt;
        }
        return prompt + block;
    }

    /** Rebuilds the exact persisted whitelist after verifying every live tool contract. */
    public AgentKernelSpec restore(
            AgentKernelSnapshotPayload payload,
            AiModel model,
            String modelFingerprint) {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(model, "model must not be null");
        String definitionKey = payload.agentDefinitionStableKey();
        String agentType = DEFAULT_AGENT_KEY.equals(definitionKey) ? null : definitionKey;
        Long ownerUserId = ownerUserId(payload.promptVariables());
        ToolSelection available = selectTools(agentType, null, null, ownerUserId);
        Map<String, AgentKernelToolManifest> availableByName = new LinkedHashMap<>();
        for (AgentKernelToolManifest tool : available.manifest()) {
            availableByName.put(tool.toolName(), tool);
        }
        List<AgentKernelToolManifest> restoredManifest = new ArrayList<>();
        Set<String> restoredWhitelist = new LinkedHashSet<>();
        for (ToolManifestSnapshot persisted : payload.tools()) {
            AgentKernelToolManifest live = availableByName.get(persisted.name());
            if (live == null
                    || !persisted.schemaSha256().equals(live.schemaSha256())
                    || persisted.readOnly() != live.readOnly()
                    || persisted.concurrencySafe() != live.concurrencySafe()
                    || !TOOL_WHITELIST_VERSION.equals(persisted.implementationVersion())) {
                throw new IllegalStateException(
                        "Persisted AgentScope tool is unavailable: " + persisted.name());
            }
            restoredManifest.add(live);
            restoredWhitelist.add(live.toolName());
        }
        AgentKernelKey key = AgentKernelKey.create(
                definitionKey,
                modelFingerprint,
                AgentKernelKey.promptVersion(
                        payload.systemPrompt(), payload.promptVariables()),
                restoredManifest,
                TOOL_WHITELIST_VERSION);
        return new AgentKernelSpec(
                key,
                model,
                definitionKey,
                payload.agentName(),
                payload.description(),
                payload.systemPrompt(),
                payload.promptVariables(),
                payload.maxIters(),
                restoredManifest,
                restoredWhitelist,
                TOOL_WHITELIST_VERSION);
    }

    private AgentKernelSpec create(
            AiModel model,
            String definitionKey,
            String agentName,
            String description,
            String systemPrompt,
            Map<String, String> promptVariables,
            ToolSelection tools,
            String modelFingerprint) {
        String safePrompt = requireText(systemPrompt, "systemPrompt");
        AgentKernelKey key = AgentKernelKey.create(
                definitionKey,
                modelFingerprint,
                AgentKernelKey.promptVersion(safePrompt, promptVariables),
                tools.manifest(),
                TOOL_WHITELIST_VERSION);
        return new AgentKernelSpec(
                key,
                model,
                definitionKey,
                agentName,
                description,
                safePrompt,
                promptVariables,
                properties.getExecution().getMaxIters(),
                tools.manifest(),
                tools.whitelist(),
                TOOL_WHITELIST_VERSION);
    }

    private ToolSelection selectTools(
            String agentType,
            List<String> requestedTools,
            List<String> requestedMcpTools,
            Long ownerUserId) {
        Set<String> requested = requestedTools == null || requestedTools.isEmpty()
                ? null
                : Set.copyOf(requestedTools);
        Set<String> requestedMcp = requestedMcpTools == null
                ? null
                : Set.copyOf(requestedMcpTools);
        AiAgentDefinition definition = agentType == null ? null : agentService.getRequiredByType(agentType);
        if (definition != null && !Integer.valueOf(1).equals(definition.getEnableTools())) {
            if (requested != null || requestedMcp != null) {
                throw new IllegalArgumentException("Agent does not enable the requested tools");
            }
            return ToolSelection.empty();
        }
        List<AgentKernelToolManifest> manifest = new ArrayList<>();
        Set<String> whitelist = new LinkedHashSet<>();
        List<ToolExecutor> directTools = agentType == null
                ? toolConfigService.getEnabledTools()
                : toolConfigService.getEnabledToolsByAgent(agentType);
        for (ToolExecutor tool : directTools) {
            if (requested != null && !requested.contains(tool.getToolName())) {
                continue;
            }
            AgentScopeToolSchema.PreparedSchema schema = AgentScopeToolSchema.prepare(
                    objectMapper, tool.getParametersSchema(), tool.getToolName());
            add(manifest, whitelist, new AgentKernelToolManifest(
                    tool.getToolName(),
                    AgentKernelToolManifest.schemaSha256(schema.canonicalJson()),
                    tool.isReadOnly(),
                    tool.isConcurrencySafe()));
        }
        List<AiAgentDefinition.SubAgentToolDef> subAgents = agentType == null
                ? List.of()
                : toolConfigService.getSubAgentTools(agentType);
        for (AiAgentDefinition.SubAgentToolDef subAgent : subAgents) {
            if (requested != null && !requested.contains(subAgent.getToolName())) {
                continue;
            }
            AgentScopeToolSchema.PreparedSchema schema = AgentScopeToolSchema.prepareSubAgent(
                    objectMapper, subAgent.getParametersSchema(), subAgent.getToolName());
            add(manifest, whitelist, new AgentKernelToolManifest(
                    subAgent.getToolName(),
                    AgentKernelToolManifest.schemaSha256(schema.canonicalJson()),
                    false,
                    true));
        }
        if (requested != null && !whitelist.equals(requested)) {
            Set<String> unavailable = new LinkedHashSet<>(requested);
            unavailable.removeAll(whitelist);
            throw new IllegalArgumentException(
                    "Requested AgentScope tools are unavailable: " + unavailable);
        }
        String definitionKey = agentType == null ? DEFAULT_AGENT_KEY : agentType;
        List<AgentKernelToolManifest> availableMcpTools = ownerUserId == null
                ? mcpRegistry.manifestsForAgent(definitionKey)
                : mcpRegistry.manifestsForAgent(definitionKey, ownerUserId);
        if (requestedMcp != null) {
            Set<String> availableMcpNames = new LinkedHashSet<>();
            availableMcpTools.forEach(tool -> availableMcpNames.add(tool.toolName()));
            Set<String> unavailableMcp = new LinkedHashSet<>(requestedMcp);
            unavailableMcp.removeAll(availableMcpNames);
            if (!unavailableMcp.isEmpty()) {
                throw new IllegalArgumentException(
                        "Requested AgentScope MCP tools are unavailable: " + unavailableMcp);
            }
        }
        for (AgentKernelToolManifest mcpTool : availableMcpTools) {
            if (requestedMcp == null || requestedMcp.contains(mcpTool.toolName())) {
                add(manifest, whitelist, mcpTool);
            }
        }
        return new ToolSelection(manifest, whitelist);
    }

    private void add(
            List<AgentKernelToolManifest> manifest,
            Set<String> whitelist,
            AgentKernelToolManifest entry) {
        if (!whitelist.add(entry.toolName())) {
            throw new IllegalArgumentException("Duplicate AgentScope tool name: " + entry.toolName());
        }
        manifest.add(entry);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static Long ownerUserId(AgentKernelSpec spec) {
        return ownerUserId(spec.promptVariables());
    }

    private static Long ownerUserId(Map<String, String> promptVariables) {
        String value = promptVariables.get(OWNER_USER_ID_VARIABLE);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid AgentScope kernel owner user id", failure);
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private record ToolSelection(
            List<AgentKernelToolManifest> manifest,
            Set<String> whitelist) {
        private ToolSelection {
            manifest = List.copyOf(manifest);
            whitelist = Set.copyOf(whitelist);
        }

        private static ToolSelection empty() {
            return new ToolSelection(List.of(), Set.of());
        }
    }
}
