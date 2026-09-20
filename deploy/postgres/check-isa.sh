#!/bin/sh
# ============================================
# ISA 守卫：断言 vector.so 的**通用代码**没有超出 x86-64 基线的指令。
#
# 为什么需要它（2026-09-20 CI 实测）：
#   pgvector 的 Makefile:15 默认 `OPTFLAGS = -march=native`。**-march=native 按构建机的
#   CPU 出码**，于是构建机上有的指令集会被编进 .so，换一台没有的机器执行就 SIGILL：
#     LOG:  server process (PID 64) was terminated by signal 4: Illegal instruction
#     DETAIL:  Failed process was running: CREATE EXTENSION IF NOT EXISTS vector;
#   Dockerfile 已用 `make OPTFLAGS=""` 修掉根因；本脚本是**防止它悄悄回来**的探针
#   （比如有人"顺手"删掉那个覆盖、或 pgvector 换版本后默认值变了）。
#
# 判据为什么是"编码前缀"而不是"助记符"或"%zmm"：
#   - 数 `%zmm` 会**漏计**：-march=native 打开的多数是 VEX 编码的 128/256 位指令，
#     objdump 里照样显示成 `%xmm`/`%ymm`。实测：加不加 -march=native，`%zmm` 都是 21 条，
#     且全在上面那两个有派发保护的函数里 —— 拿它当判据，真正的破坏一条都抓不到。
#   - 正确且与架构无关的判据是**指令的首字节**：VEX = 0xC4/0xC5，EVEX = 0x62。
#     64 位模式下这三个字节不可能是别的指令（32 位的 LES/LDS/BOUND 在 64 位非法）。
#   - 同一条 `addps`，老编码是 `0F 58`（SSE，x86-64 基线永远可用），VEX 编码是 `C5 F8 58`
#     （**要求 AVX**）。看指令名分不出这两者，看首字节一眼分得出。
#
# ★ 但"首字节"判据有盲区，必须配一张助记符黑名单（下方 DENY）：
#   `movbe`（编码 66 44 0f 38 f1 28）和 `lzcnt`（编码 f3 48 0f bd c8）是
#   **老编码的新操作码** —— 首字节不是 c4/c5/62，VEX 判据看不见它们。
#   同类还有 popcnt / crc32 / aes* / sha* / pclmulqdq / adcx / adox 等。
#   实测：旧镜像里这些确实存在，只是因为它们恰好和 VEX 一起出现才被发现。
#
# 白名单是怎么来的（实测，不是猜的）：
#   用 `OPTFLAGS=""` 构建后仍含 VEX/EVEX 的函数**只有 6 个**，全部带显式 CPU 标注
#   且**有 CPUID 运行时派发保护**，在没有对应指令集的机器上根本不会被调用：
#     HalfvecCosineSimilarityF16c / HalfvecL1DistanceF16c /
#     HalfvecL2SquaredDistanceF16c / HalfvecInnerProductF16c   ← __attribute__((target_clones("f16c", ...)))
#     BitJaccardDistanceAvx512Popcount / BitHammingDistanceAvx512Popcount
#                                                              ← target("avx512f,avx512vpopcntdq")
#                                                                 由 SupportsAvx512Popcount() 守卫
#   对照：加 `-march=native` 的旧镜像里，含 VEX/EVEX 的函数有 **177 个**，且全是
#   `vector_in` / `vector_out` / `vector_eq` / `cosine_distance` 这类**无派发保护的核心路径**。
#
# 若将来 pgvector 新增了别的带 CPU 标注的内核，本脚本会**响亮地失败**（假阳性）。
# 这是刻意选的方向：假阳性是看一眼就能解决的，假阴性是静默产出只在部分机器上能跑的镜像。
# ============================================
set -eu

SO="${1:-/usr/local/lib/postgresql/vector.so}"

if [ ! -f "$SO" ]; then
    echo "ISA 守卫：找不到 $SO" >&2
    exit 1
fi

bad=$(objdump -d "$SO" | awk '
    BEGIN {
        FS = "\t"
        # 老编码的新操作码：VEX 判据看不见，必须逐名列出。
        # 只列**会因缺失而 #UD(SIGILL) 或算错**的那批，不含纯性能指令。
        n = split("movbe lzcnt tzcnt popcnt crc32 adcx adox" \
                  " aesenc aesenclast aesdec aesdeclast aesimc aeskeygenassist" \
                  " pclmulqdq" \
                  " sha1rnds4 sha1nexte sha1msg1 sha1msg2 sha256rnds2 sha256msg1 sha256msg2" \
                  " rdrand rdseed rdpid rdfsbase rdgsbase wrfsbase wrgsbase" \
                  " movdiri movdir64b", a, " ")
        for (i = 1; i <= n; i++) deny[a[i]] = 1
    }
    # 注意：符号行里**没有 tab**，不能用 $2 取函数名（FS 已经是 \t，
    # 那样取到的是空串 → 白名单被静默架空 → 守卫退化成"永远报错"）。
    # 实测踩过：必须先切好 FS，再用 match 从整行里抠 <name>。
    /^[0-9a-f]+ </ { fn = "?"; if (match($0, /<[^>]*>/)) fn = substr($0, RSTART, RLENGTH) }
    NF >= 3 {
        b = $2; gsub(/^[[:space:]]+|[[:space:]]+$/, "", b)
        split(b, by, /[[:space:]]+/)

        # 判据 1：VEX/EVEX 编码（要求 AVX 家族）
        if (by[1] == "c4" || by[1] == "c5" || by[1] == "62") {
            if (fn !~ /Avx512|F16c/) hit[fn]++
            next
        }

        # 判据 2：老编码的新操作码（VEX 判据的盲区）
        m = $3; sub(/[[:space:]].*$/, "", m)
        if (m in deny) hit[fn]++
    }
    END { for (f in hit) printf "%6d  %s\n", hit[f], f }
' | sort -rn)

if [ -n "$bad" ]; then
    {
        echo "ISA 守卫失败：$SO 的通用代码里出现了 VEX/EVEX 编码指令（0xC4/0xC5/0x62）。"
        echo "这通常意味着 pgvector 又被用 -march=native 编了 —— 该镜像只能在"
        echo "构建机同款的 CPU 上运行，换机器执行 CREATE EXTENSION vector 会 SIGILL。"
        echo "修法：确认 Dockerfile 里是 \`make OPTFLAGS=\"\" with_llvm=no\`（勿改成别的值）。"
        echo "涉事函数（指令条数  函数名）："
        echo "$bad"
    } >&2
    exit 1
fi

echo "ISA 守卫通过：$SO 的通用代码未超出 x86-64 基线（VEX/EVEX 仅出现在有 CPU 派发保护的函数里）。"
