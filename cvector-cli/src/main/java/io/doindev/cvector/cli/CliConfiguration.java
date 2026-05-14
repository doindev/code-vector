package io.doindev.cvector.cli;

import io.doindev.cvector.core.Parser;
import io.doindev.cvector.core.config.CvectorConfigService;
import io.doindev.cvector.parser.bicep.BicepParserAdapter;
import io.doindev.cvector.parser.c.CParserAdapter;
import io.doindev.cvector.parser.config.EnvParserAdapter;
import io.doindev.cvector.parser.config.JsonParserAdapter;
import io.doindev.cvector.parser.config.PomParserAdapter;
import io.doindev.cvector.parser.config.YamlParserAdapter;
import io.doindev.cvector.parser.cpp.CppParserAdapter;
import io.doindev.cvector.parser.csharp.CSharpParserAdapter;
import io.doindev.cvector.parser.cypher.CypherParserAdapter;
import io.doindev.cvector.parser.docker.DockerfileParserAdapter;
import io.doindev.cvector.parser.go.GoParserAdapter;
import io.doindev.cvector.parser.graphql.GraphqlParserAdapter;
import io.doindev.cvector.parser.hive.HiveParserAdapter;
import io.doindev.cvector.parser.java.JavaParserAdapter;
import io.doindev.cvector.parser.javascript.JavaScriptParserAdapter;
import io.doindev.cvector.parser.kotlin.KotlinParserAdapter;
import io.doindev.cvector.parser.php.PhpParserAdapter;
import io.doindev.cvector.parser.plsql.PlSqlParserAdapter;
import io.doindev.cvector.parser.protobuf.ProtobufParserAdapter;
import io.doindev.cvector.parser.python.PythonParserAdapter;
import io.doindev.cvector.parser.rust.RustParserAdapter;
import io.doindev.cvector.parser.solidity.SolidityParserAdapter;
import io.doindev.cvector.parser.sparql.SparqlParserAdapter;
import io.doindev.cvector.parser.sql.SqlParserAdapter;
import io.doindev.cvector.parser.style.StylesheetParserAdapter;
import io.doindev.cvector.parser.terraform.TerraformParserAdapter;
import io.doindev.cvector.parser.ts.TypeScriptParserAdapter;
import io.doindev.cvector.parser.tsql.TSqlParserAdapter;
import io.doindev.cvector.parser.vue.VueSfcParserAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CliConfiguration {

    @Bean
    public CvectorConfigService cvectorConfigService() {
        return new CvectorConfigService();
    }

    @Bean public Parser javaParserAdapter() { return new JavaParserAdapter(); }
    @Bean public Parser sqlParserAdapter() { return new SqlParserAdapter(); }
    @Bean public Parser pomParserAdapter() { return new PomParserAdapter(); }
    @Bean public Parser yamlParserAdapter() { return new YamlParserAdapter(); }
    @Bean public Parser jsonParserAdapter() { return new JsonParserAdapter(); }
    @Bean public Parser envParserAdapter() { return new EnvParserAdapter(); }
    @Bean public TypeScriptParserAdapter typeScriptParserAdapter() { return new TypeScriptParserAdapter(); }
    @Bean public StylesheetParserAdapter stylesheetParserAdapter() { return new StylesheetParserAdapter(); }
    @Bean public Parser vueSfcParserAdapter(TypeScriptParserAdapter ts, StylesheetParserAdapter css) {
        return new VueSfcParserAdapter(ts, css);
    }
    @Bean public Parser pythonParserAdapter() { return new PythonParserAdapter(); }
    @Bean public Parser terraformParserAdapter() { return new TerraformParserAdapter(); }
    @Bean public Parser dockerfileParserAdapter() { return new DockerfileParserAdapter(); }
    @Bean public Parser cSharpParserAdapter() { return new CSharpParserAdapter(); }
    @Bean public Parser rustParserAdapter() { return new RustParserAdapter(); }
    @Bean public Parser goParserAdapter() { return new GoParserAdapter(); }
    // JavaScriptParserAdapter is intentionally NOT registered: the TypeScript parser claims
    // the same extension set (.js/.jsx/.mjs/.cjs) and TS is a strict superset of JS, so every
    // .js file would be parsed twice under the multi-dispatch model. The dedicated JS adapter
    // remains in the codebase as an alternative the user can swap in via CliConfiguration if
    // they want a JS-only build without the TS grammar's overhead.
    @Bean public Parser kotlinParserAdapter() { return new KotlinParserAdapter(); }
    @Bean public Parser cParserAdapter() { return new CParserAdapter(); }
    @Bean public Parser cppParserAdapter() { return new CppParserAdapter(); }
    @Bean public Parser solidityParserAdapter() { return new SolidityParserAdapter(); }
    @Bean public Parser phpParserAdapter() { return new PhpParserAdapter(); }
    @Bean public Parser graphqlParserAdapter() { return new GraphqlParserAdapter(); }
    @Bean public Parser protobufParserAdapter() { return new ProtobufParserAdapter(); }
    @Bean public Parser bicepParserAdapter() { return new BicepParserAdapter(); }
    @Bean public Parser cypherParserAdapter() { return new CypherParserAdapter(); }
    @Bean public Parser sparqlParserAdapter() { return new SparqlParserAdapter(); }
    @Bean public Parser plSqlParserAdapter() { return new PlSqlParserAdapter(); }
    @Bean public Parser tSqlParserAdapter() { return new TSqlParserAdapter(); }
    @Bean public Parser hiveParserAdapter() { return new HiveParserAdapter(); }
}
