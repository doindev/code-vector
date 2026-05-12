package io.doindev.cvector.parser.terraform;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerraformParserAdapterTest {

    @Test
    void emitsResourcesDataModulesAndProviders(@TempDir Path root) throws Exception {
        Path file = root.resolve("main.tf");
        Files.writeString(file, """
                provider "aws" {
                  region = "us-east-1"
                }

                variable "env" { default = "prod" }
                output "bucket_name" { value = aws_s3_bucket.assets.bucket }

                data "aws_iam_policy_document" "assume_role" {
                  statement {
                    actions = ["sts:AssumeRole"]
                  }
                }

                resource "aws_s3_bucket" "assets" {
                  bucket = "my-bucket"
                }

                resource "aws_iam_role" "app" {
                  name               = "app-role"
                  assume_role_policy = data.aws_iam_policy_document.assume_role.json
                }

                resource "aws_db_instance" "users_db" {
                  identifier     = "users"
                  vpc_security_group_ids = [aws_security_group.db.id]
                }

                resource "aws_security_group" "db" {
                  name = "db-sg"
                }

                module "network" {
                  source = "./modules/network"
                  env    = var.env
                }
                """);

        List<GraphEvent> events = parse(root, file);

        assertThat(nodeFqNames(events, "Resource"))
                .contains("aws_s3_bucket.assets", "aws_iam_role.app",
                        "aws_db_instance.users_db", "aws_security_group.db");
        assertThat(nodeFqNames(events, "DataSource"))
                .contains("data.aws_iam_policy_document.assume_role");
        assertThat(nodeFqNames(events, "TerraformModule")).contains("module.network");
        assertThat(nodeFqNames(events, "TerraformVariable")).contains("var.env");
        assertThat(nodeFqNames(events, "TerraformOutput")).contains("output.bucket_name");
        assertThat(nodeFqNames(events, "TerraformProvider")).contains("provider.aws");
    }

    @Test
    void emitsCrossResourceUsesEdges(@TempDir Path root) throws Exception {
        Path file = root.resolve("main.tf");
        Files.writeString(file, """
                data "aws_iam_policy_document" "assume_role" {}

                resource "aws_iam_role" "app" {
                  assume_role_policy = data.aws_iam_policy_document.assume_role.json
                }

                resource "aws_db_instance" "users_db" {
                  vpc_security_group_ids = [aws_security_group.db.id]
                }

                resource "aws_security_group" "db" {
                  name = "db-sg"
                }
                """);
        List<GraphEvent> events = parse(root, file);

        List<String> usesEdges = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "USES".equals(eu.type()))
                .map(e -> {
                    GraphEvent.EdgeUpsert eu = (GraphEvent.EdgeUpsert) e;
                    return eu.from().fqName() + " -> " + eu.to().fqName();
                })
                .toList();

        assertThat(usesEdges).contains("aws_iam_role.app -> data.aws_iam_policy_document.assume_role");
        assertThat(usesEdges).contains("aws_db_instance.users_db -> aws_security_group.db");
    }

    @Test
    void emitsVarReferencesAsUsesEdges(@TempDir Path root) throws Exception {
        Path file = root.resolve("vars.tf");
        Files.writeString(file, """
                variable "bucket_name" {}

                resource "aws_s3_bucket" "site" {
                  bucket = var.bucket_name
                }
                """);
        List<GraphEvent> events = parse(root, file);
        List<String> usesEdges = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "USES".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName() + " -> " + ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();
        assertThat(usesEdges).contains("aws_s3_bucket.site -> var.bucket_name");
    }

    @Test
    void ignoresBlocksInsideHashAndSlashComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("audit.tf");
        Files.writeString(file, """
                # resource "aws_s3_bucket" "commented_hash" { bucket = "fake" }
                // resource "aws_s3_bucket" "commented_slash" { bucket = "fake" }
                /* resource "aws_s3_bucket" "commented_block" {
                     bucket = "fake"
                   }
                   data "aws_ami" "commented_data" {}
                */
                resource "aws_s3_bucket" "real" {
                  bucket = "real-bucket"
                }
                """);
        List<GraphEvent> events = parse(root, file);

        List<String> resources = nodeFqNames(events, "Resource");
        List<String> data = nodeFqNames(events, "DataSource");
        assertThat(resources).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(data).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(resources).contains("aws_s3_bucket.real");
    }

    @Test
    void doesNotMatchResourceRefInsideStringLiterals(@TempDir Path root) throws Exception {
        Path file = root.resolve("strings.tf");
        Files.writeString(file, """
                resource "aws_s3_bucket" "a" {
                  bucket = "literal aws_s3_bucket.not_a_ref string"
                  tags = {
                    note = "see aws_db_instance.fake_ref"
                  }
                }

                resource "aws_s3_bucket" "b" {
                  bucket = "${aws_s3_bucket.a.id}"
                }
                """);
        List<GraphEvent> events = parse(root, file);

        List<String> usesEdges = events.stream()
                .filter(e -> e instanceof GraphEvent.EdgeUpsert eu && "USES".equals(eu.type()))
                .map(e -> ((GraphEvent.EdgeUpsert) e).from().fqName() + " -> " + ((GraphEvent.EdgeUpsert) e).to().fqName())
                .toList();

        assertThat(usesEdges).contains("aws_s3_bucket.b -> aws_s3_bucket.a");
        assertThat(usesEdges).noneMatch(s -> s.contains("fake_ref") || s.contains("not_a_ref"));
    }

    @Test
    void capturesModuleSourceAttribute(@TempDir Path root) throws Exception {
        Path file = root.resolve("modules.tf");
        Files.writeString(file, """
                module "vpc" {
                  source  = "terraform-aws-modules/vpc/aws"
                  version = "5.0.0"
                }
                """);
        List<GraphEvent> events = parse(root, file);
        GraphEvent.NodeUpsert mod = (GraphEvent.NodeUpsert) events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && "TerraformModule".equals(u.key().label()))
                .findFirst()
                .orElseThrow();
        assertThat(mod.props()).containsEntry("source", "terraform-aws-modules/vpc/aws");
    }

    private static List<String> nodeFqNames(List<GraphEvent> events, String label) {
        return events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && label.equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new TerraformParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
