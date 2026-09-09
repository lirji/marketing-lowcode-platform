package com.acme.marketing.referral;

import com.acme.marketing.contracts.release.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 独立 JVM 恢复探针，仅测试 classpath；配置从标准输入读取，不使用项目 .env 或共享数据库。 */
public final class ReferralRuntimeRestartProbe {
    /** 重建公钥及验证器，从库中恢复指令和制品，不继承父 JVM 的缓存或 READY 夹具。 */
    public static void main(String[] args) throws Exception {
        var json=JsonMapper.builder().build();
        Input input=json.readValue(new String(System.in.readAllBytes(),StandardCharsets.UTF_8),Input.class);
        var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(input.publicKey())));
        var directiveVerifier=new ReferralRuntimeDirectiveVerifier(Map.of("key",key),"test","cell","default");
        try(var connection=DriverManager.getConnection(input.url(),input.user(),input.password())) {
            ActivationDirective activation;
            try(var query=connection.prepareStatement("SELECT sequence_no,directive_json FROM mk_referral_runtime_cursor WHERE tenant_id=? AND stream_kind='ACTIVATION' AND environment='test' AND cell='cell' AND namespace='default'")) {
                query.setString(1,input.tenant()); try(var rows=query.executeQuery()) {
                    if(!rows.next() || rows.getLong(1)!=input.sequence())throw new IllegalStateException("cursor not recovered");
                    activation=json.readValue(rows.getString(2),ActivationDirective.class);
                }
            }
            if(activation.activationSequence()!=input.sequence() || activation.generation()!=input.generation())throw new IllegalStateException("wrong recovered version");
            try(var query=connection.prepareStatement("SELECT release_key_id,manifest_json,artifact_payload FROM mk_referral_verified_release WHERE tenant_id=? AND environment='test' AND cell='cell' AND namespace='default' AND generation=?")) {
                query.setString(1,input.tenant()); query.setLong(2,input.generation());
                try(var rows=query.executeQuery()) {
                    if(!rows.next())throw new IllegalStateException("artifact missing");
                    var manifest=json.readValue(rows.getString(2),ReleaseManifest.class);
                    new ReferralReleaseVerifier(Map.of("key",key),Map.of("key",key),"test","cell","default")
                        .verifyInstallation(input.tenant(),rows.getString(1),manifest,rows.getBytes(3),input.now());
                    directiveVerifier.activation(input.tenant(),rows.getString(1),manifest,activation,input.now());
                }
            }
        }
        // 只报告恢复事实，没有外部 READY，不输出任何参与许可或连接配置。
        System.out.println("RESTORED " + input.sequence() + " " + input.generation());
    }
    /** 所有字段来自隔离测试容器；不允许从环境自动发现共享目标。 */
    public record Input(String url,String user,String password,String tenant,String publicKey,Instant now,long sequence,long generation) {}
}
