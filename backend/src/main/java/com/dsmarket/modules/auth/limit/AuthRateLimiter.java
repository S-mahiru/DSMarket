package com.dsmarket.modules.auth.limit;

/**
 * 登录 / 注册限流（审计 §2.4）。
 *
 * <p>相比原先"按用户名、每次尝试都计数、成功即清零"的做法，本接口把三件事分开：</p>
 * <ol>
 *   <li><b>账号维度取 (账号, IP) 对，而不是裸账号</b> —— 这一条修的才是<b>反向账号锁定 DoS</b>：
 *       裸账号维度下"锁定"与"攻击者是谁"无关，攻击者对任意已知用户名连发垃圾请求，
 *       被锁的是全体；取对之后他只能锁住"自己那个 IP 打这个账号"这一格，
 *       受害者从自己的 IP 照常登录。而那一步不需要猜中任何口令。</li>
 *   <li><b>只对失败计数</b>（{@link #onLoginFailure}）。这一条<b>不是</b>用来修锁定 DoS 的
 *       （在纯失败的序列上，"每次尝试都计数"与"只对失败计数"完全等价：都是第 limit 次
 *       失败之后拦第 limit+1 次）。它防的是另一件事：<b>成功也要计数时，正常用户反复登录
 *       会把自己的出口 IP 送进封锁</b> —— 而 IP 桶刻意不因成功而清零（见
 *       {@link #onLoginSuccess}），于是校园网/办公室这类共享出口会被自己人打满，
 *       全员登不进来。故成功登录在 IP 维度上必须<b>不留痕</b>。</li>
 *   <li><b>封锁时长随失败次数递增</b>（见 {@code RedisAuthRateLimiter#blockSeconds}），
 *       使持续尝试的代价单调上升，而不是永远 60 秒一循环。</li>
 * </ol>
 *
 * <p>第 2 条的措辞是<b>被反向突变校准过的</b>：最初这里写的是"失败才计数修的是锁定 DoS"，
 * 而把这个改动反向突变掉（改回每次尝试都计数）后，全部用例照样绿 —— 说明那个因果是错的，
 * 测试也没打在承重处。补了"{@code 成功登录不把自己的 IP 送进封锁}"这条用例之后，
 * 该突变才转红。</p>
 *
 * <p>校验语义为"失败达到阈值即封锁"，故 {@code limit} 次失败本身<b>是被处理的</b>
 * （第 limit 次仍回业务错误 400），被拒的是第 {@code limit + 1} 次起的请求。</p>
 */
public interface AuthRateLimiter {

    /** 登录前置检查。命中封锁则抛 429，附带剩余等待秒数。 */
    void checkLogin(String username, String clientIp);

    /** 登录失败：记一笔，达阈值即写入封锁标记。 */
    void onLoginFailure(String username, String clientIp);

    /** 登录成功：清掉该 (账号, IP) 对的失败计数。 */
    void onLoginSuccess(String username, String clientIp);

    /**
     * 注册前置检查。
     *
     * <p>与登录相反，这里按 <b>尝试次数</b>计（含成功）：注册本身就要落库 + BCrypt，
     * 它是需要被节流的动作本身，而不是"猜错了才算的"动作。计数与判定在同一步完成。</p>
     */
    void checkRegister(String clientIp);
}
