package com.agenticrag.tool.tools;

import com.agenticrag.dataanalysis.DemoSchemaService;
import com.agenticrag.tool.Tool; 
import com.agenticrag.tool.ToolDomain; 
import com.agenticrag.tool.ToolRegistry; 
import jakarta.annotation.PostConstruct; 
import lombok.RequiredArgsConstructor; 
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; 
import org.springframework.stereotype.Component; 
import java.util.Map;
import java.util.Set;


/**
 * 表结构发现工具（F2）：输出 demo 库所有表的列、类型与样本值。
 * 不硬编码任何表名/字段名（N4 数据可替换：换数据集只需换数据与 COMMENT，工具代码零改动）。
 */
@Component 
@RequiredArgsConstructor 
@ConditionalOnProperty(prefix = "rag.data", name = "enabled", havingValue = "true")
public class ListTablesTool implements Tool {
    
    private static final String NAME = "list_tables";
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "table": {
                  "type": "string",
                  "description": "可选：指定单表名时仅返回该表详情"
                }
              }
            }
            """;

    private final DemoSchemaService demoSchemaService;
    private final ToolRegistry toolRegistry;

    @PostConstruct 
    public void register() {
        toolRegistry.register(this);
    }

    @Override public String name () { 
        return NAME;
    } 
    
    @Override 
    public String description () { 
        return "查看可查询业务表的表结构、字段类型与 2 行样本值；写 run_sql 前应先调用本工具确认表名和列名。";
    } 

    @Override public String parametersSchema () { 
        return SCHEMA;
    }

    @Override public Set<ToolDomain> domains () {
        return Set.of(ToolDomain.SQL);
    }

    @Override public String execute (Map<String, Object> arguments) { try { Object tableArg = arguments == null ? null : arguments.get( "table" ); if (tableArg != null && !tableArg.toString().isBlank()) { return demoSchemaService.describeTable(tableArg.toString().trim());
            } return demoSchemaService.describeAll();
        } catch (Exception e) { return "查询表结构失败，请稍后重试或检查数据源配置。" ;
        }
    }

}
