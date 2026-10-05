package com.agenticrag.tool.tools;

import com.agenticrag.tool.Tool; 
import com.agenticrag.tool.ToolDomain; 
import com.agenticrag.tool.ToolRegistry; 
import jakarta.annotation.PostConstruct; 
import lombok.RequiredArgsConstructor; 
import org.springframework.beans.factory.annotation.Qualifier; 
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; 
import org.springframework.jdbc.core.JdbcTemplate; 
import org.springframework.stereotype.Component; 
import java.util.List; 
import java.util.Map;
import java.util.Set;


/**
 * 表结构发现工具（F2）：读取 information_schema + 字段 COMMENT，动态列出 demo 库所有表。
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

    private static final String TABLES_SQL = """
            SELECT t.table_name, obj_description(c.oid) AS table_comment
            FROM information_schema.tables t
            JOIN pg_class c ON c.relname = t.table_name
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema
            WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
            ORDER BY t.table_name
            """ ; 

    private static final String COLUMNS_SQL = """
            SELECT column_name, data_type, col_description(
                (quote_ident(table_schema) || '.' || quote_ident(table_name))::regclass::oid,
                ordinal_position
            ) AS column_comment
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
            """ ;
    
    @Qualifier("demoJdbcTemplate")
    private final JdbcTemplate demoJdbcTemplate;
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
        return "列出数据库中所有表及其字段名、类型、中文含义，用于理解库结构（schema 发现）" ;
    } 

    @Override public String parametersSchema () { 
        return SCHEMA;
    }

    @Override public Set<ToolDomain> domains () {
        return Set.of(ToolDomain.SQL);
    }

    @Override public String execute (Map<String, Object> arguments) { try { Object tableArg = arguments == null ? null : arguments.get( "table" ); if (tableArg != null && !tableArg.toString().isBlank()) { return renderSingleTable(tableArg.toString().trim());
            } return renderAllTables();
        } catch (Exception e) { return "查询表结构失败，请稍后重试或检查数据源配置。" ;
        }
    }

    private String renderAllTables() {
        List<Map<String, Object>> tables = demoJdbcTemplate.queryForList(TABLES_SQL);
        if (tables.isEmpty()) {
            return "当前数据库中没有表。";
        }
       StringBuilder sb = new StringBuilder();
       sb.append( "数据库共有 " ).append(tables.size()).append( " 张表：\n" );
       for (Map<String, Object> t : tables) {
           String tableName = String.valueOf(t.get("table_name"));
           String comment = t.get( "table_comment" ) == null ? "" : String.valueOf(t.get( "table_comment" ));
           sb.append("\n## ").append(tableName);
           if (!comment.isBlank()) {
               sb.append( "（" ).append(comment).append( "）" );
           }
           sb.append("\n");
           appendColumns(sb, tableName);
       }
       return sb.toString();
    }

    private String renderSingleTable (String tableName) { 
        StringBuilder sb = new StringBuilder ();
        sb.append( "表 " ).append(tableName).append( " 的字段：\n" );
        appendColumns(sb, tableName); 
        return sb.toString();
    }

    private void appendColumns (StringBuilder sb, String tableName) { List <Map<String, Object>> columns = demoJdbcTemplate.queryForList(COLUMNS_SQL, tableName); if (columns.isEmpty()) {
            sb.append( "  （未找到该表或无字段）\n" ); return ;
        } for (Map<String, Object> col : columns) { String comment = col.get( "column_comment" ) == null ? "" : String.valueOf(col.get( "column_comment" ));
            sb.append( "  - " ).append(col.get( "column_name" ))
                    .append( " (" ).append(col.get( "data_type" )).append( ")" ); if (!comment.isBlank()) {
                sb.append( "：" ).append(comment);
            }
            sb.append( "\n" );
        }
    }

}
