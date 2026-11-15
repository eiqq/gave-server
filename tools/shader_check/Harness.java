// 26.3 GlslCompiler 와 같은 shaderc 옵션으로 셰이더를 SPIR-V 로 컴파일해 본다(검사만, 결과는 버림).
// 인자: <뿌리들 ; 로 구분, 앞이 먼저> <일감 파일: 줄마다 "core/item.fsh f NAME,NAME=VAL,...">
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;

public class Harness {
    static final List<Path> ROOTS = new ArrayList<>();

    static Path find(String ns, String rel) {
        for (Path r : ROOTS) {
            Path p = r.resolve("assets").resolve(ns).resolve("shaders").resolve(rel);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        for (String r : a[0].split(";")) ROOTS.add(Path.of(r));
        List<String> jobs = Files.readAllLines(Path.of(a[1]));
        long compiler = shaderc_compiler_initialize();
        ShadercIncludeResolve resolve = ShadercIncludeResolve.create((ud, req, type, reqing, depth) -> {
            String name = memUTF8(req);
            int c = name.indexOf(':');
            Path p = c < 0 ? null : find(name.substring(0, c), "include/" + name.substring(c + 1));
            ShadercIncludeResult res = ShadercIncludeResult.calloc();
            try {
                res.source_name(memUTF8(p == null ? "" : name, false));
                res.content(memUTF8(p == null ? "not found" : Files.readString(p), false));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            res.user_data(0);
            return res.address();
        });
        ShadercIncludeResultRelease release = ShadercIncludeResultRelease.create((ud, addr) -> {
            ShadercIncludeResult res = ShadercIncludeResult.create(addr);
            nmemFree(memGetAddress(addr + ShadercIncludeResult.SOURCE_NAME));
            nmemFree(memGetAddress(addr + ShadercIncludeResult.CONTENT));
            res.free();
        });
        int fails = 0, n = 0;
        for (String job : jobs) {
            if (job.isBlank()) continue;
            String[] f = job.trim().split("\\s+");
            Path src = find("minecraft", f[0]);
            int kind = f[1].equals("v") ? shaderc_vertex_shader : shaderc_fragment_shader;
            long opts = shaderc_compile_options_initialize();
            shaderc_compile_options_set_target_env(opts, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_auto_bind_uniforms(opts, true);
            shaderc_compile_options_set_preserve_bindings(opts, false);
            shaderc_compile_options_set_generate_debug_info(opts);
            shaderc_compile_options_set_optimization_level(opts, shaderc_optimization_level_zero);
            if (f.length > 2 && !f[2].equals("-")) {
                for (String d : f[2].split(",")) {
                    String[] kv = d.split("=", 2);
                    shaderc_compile_options_add_macro_definition(opts, kv[0], kv.length > 1 ? kv[1] : "");
                }
            }
            shaderc_compile_options_set_include_callbacks(opts, resolve, release, 0L);
            ByteBuffer text = memUTF8(Files.readString(src), false);
            ByteBuffer file = memUTF8("minecraft:" + f[0]);
            ByteBuffer entry = memUTF8("main");
            long r = shaderc_compile_into_spv(compiler, text, kind, file, entry, opts);
            int st = shaderc_result_get_compilation_status(r);
            long warn = shaderc_result_get_num_warnings(r);
            long bytes = shaderc_result_get_length(r);
            n++;
            if (st != shaderc_compilation_status_success) fails++;
            System.out.println((st == shaderc_compilation_status_success ? "OK   " : "FAIL ") + job.trim() + "  (spv " + bytes + " B, 경고 " + warn + ")"
                    + " <- " + src);
            String msg = shaderc_result_get_error_message(r);
            if (msg != null && !msg.isBlank()) System.out.println("     " + msg.strip().replace("\n", "\n     "));
            shaderc_result_release(r);
            shaderc_compile_options_release(opts);
            memFree(text);
            memFree(file);
            memFree(entry);
        }
        shaderc_compiler_release(compiler);
        System.out.println("합계 " + n + ", 실패 " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
