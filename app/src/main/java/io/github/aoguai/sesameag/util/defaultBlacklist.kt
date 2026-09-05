package io.github.aoguai.sesameag.util

/**
 * 默认黑名单列表（包含常见无法完成、暂无稳定 RPC 或长期仅支持手动完成的任务）
 */

private val sesameCreditDefaultBlacklist =
    setOf(
        // 芝麻信用 / 芝麻粒
        "zml_zijie_toutiaozhuduan_sanfang", // 今日头条唤端任务，缺少稳定完成RPC闭环
        "AP17345296", // 芝麻树 NONE_SIGNUP 未知状态
        "zml_diantao_renwu_sanfang",
        "zml_eduka_renwu",
        "zml_zfbfeizhu_xiadan_sanfang",
        // 芝麻树 SIGNUP_SEND 游戏/导流任务
        "AP11327686",
        "AP18365439",
        "AP18344041",
        "AP15358968",
        "AP14359058",
        "AP18344357",
        "AP13358931",
        "AP16358982",
        "AP13358969",
        "AP17344131",
        "AP17359071",
        "AP11313161",
        "AP13350341",
        "AP11327894",
        "AP19361153",
        "AP11287911",
        "zml_mybx_xiadan_erfang",
        "zml_check_in_subscribe_task", // joinActivity 返回 PROMISE_TEMPLATE_NOT_EXIST
        "zml_set_home_task", // joinActivity 返回 PROMISE_TEMPLATE_NOT_EXIST
        "zml_zmzl_xdrw_erfang", // pushActivity 返回 ILLEGAL_ARGUMENT，需真实租赁下单
        "zml_tbbbnc_shifei_sanfang", // pushActivity 返回 ILLEGAL_ARGUMENT，需真实淘宝行为
        "zmxy_zml_wannengxiaozujian", // pushActivity 返回 ILLEGAL_ARGUMENT，需真实桌面组件行为
        "zml_cainiao_guojiang_sanfang", // pushActivity 返回 ILLEGAL_ARGUMENT，需真实菜鸟行为
        // 芝麻树 RENT 游戏
        "AP19359169",
        "AP10359017",
        "AP11359168",
        "AP12359059",
        "AP16358996",
        "AP11358913",
        "AP12359016",
    )

private val sesameAlchemyDefaultBlacklist =
    setOf(
        // 芝麻炼金
        "hjwf_zcylt_zhuanhua",
        "hjwf_eduka_renwu",
        "alchemy_check_in_subscribe_task", // joinActivity 返回 PROMISE_TEMPLATE_NOT_EXIST
        "hjwf_tbqd_qiandao_sanfang",
        "hjwf_tbbbnc_shifei_sanfang",
    )

private val orchardDefaultBlacklist =
    setOf(
        // 芭芭农场
        "ORCHARD_NORMAL_KUAISHOU_MAX", // 逛一逛快手
        "ORCHARD_NORMAL_DIAOYU1", // 钓鱼1次
        "ZHUFANG3IN1", // 添加农场小组件并访问
        "70000|逛好物最高得1500肥料", // XLight广告流量风控，缺少稳定自动闭环
        "12173", // 买好货
        "TOUTIAO|逛一逛今日头条", // 精确匹配旧今日头条任务，避免误伤趣头条任务
        "ORCHARD_NORMAL_JIUYIHUISHOU_VISIT", // 旧衣服回收
        "ORCHARD_NORMAL_SHOUJISHUMAHUISHOU", // 数码回收
        "ORCHARD_NORMAL_TAB3_ZHIFA", // 看视频领肥料
        "ORCHARD_NORMAL_AQ_XIAZAI", // 下载蚂蚁阿福看健康攻略
        "ncflzhrw51", // 去游戏中心抢金条：不支持rpc调用
        "babafarm_cjmk_xdujdd15", // 去游戏中心玩游戏：不支持rpc调用
        "LINGHUOTIAOKONG", // 逛一逛新浪微博
        "ANTFARM_ORCHARD_NORMAL_YITAO", // 逛一逛一淘
        "ANTFARM_ORCHARD_NORMAL_CAINIAO_DUAN", // 菜鸟任务 finishTask 返回 400000040
        "ORCHARD_NCLY_ZH_MSQYJ_V3", // 美食趣味记依赖真实游戏事件
        "ORCHARD_NCLY_ZH_DDPLY_V3", // 对对碰乐园依赖真实游戏事件
        "ORCHARD_NCLY_GAME_CHARGE0|任意充值得100000肥", // finishTask 返回 400000040，不支持rpc调用
        "ORCHARD_NORMAL_GAODE_VISIT|去高德发表真实评价", // finishTask 返回 400000040，不支持rpc调用
        "ORCHARD_NORMAL_SHANGOUMIANDAN|逛一逛淘宝闪购", // finishTask 返回 400000040，不支持rpc调用
        "ORCHARD_NORMAL_TAOBAOTAOLIPAI_VISIT|逛一逛淘宝拍照", // 不支持rpc调用
        "ORCHARD_NORMAL_TAOBAO26_618|去淘金币赢20亿", // 不支持rpc调用，缺少稳定完成RPC闭环
        "ORCHARD_NORMAL_WAIMAIMIANDAN", // 逛一逛闪购外卖
        "ORCHARD_NORMAL_BAIDU_DUO", // 去百度浏览资讯
        "ORCHARD_NORMAL_XIANXIAZHIFU100", // 到店支付1笔得100肥
        "ANTFARM_ORCHARD_P2P_SHARER", // 分享给好友
        "ORCHARD_TEAM_SPREAD_PERSON", // 合种/帮帮种多人施肥
        "ORCHARD_HELP_TEAM_MEMBER_COUNT", // 帮帮种组队
        "NTFARM_ORCHARD_NORMAL_FQHB_NEW1", // 去天猫攒福气兑红包
        "龙迹之城击杀10只怪物",
        "寻道大千砍树20次",
        "点击立得，最高3500肥",
        "分享给好友",
        "合种/帮帮种多人施肥",
        "帮帮种组队",
        "去天猫攒福气兑红包",
        "去游戏中心抢金条",
    )

private val goldenBeanDefaultBlacklist =
    setOf(
        "GOLDEN_BEAN_MASTER_TASK:TEST_PUSH_SUBSCRIBE", // 订阅依赖真实用户确认
        "GOLDEN_BEAN_MASTER_TASK:GOLDEN_BEAN_TASK_XIANSHANGZHIFU", // 线上支付
        "GOLDEN_BEAN_MASTER_TASK:GOLDEN_BEAN_TASK_XIANXIAZHIFU", // 线下支付
        "GOLDEN_BEAN_MASTER_TASK:GOLDEN_BEAN_TASK_YUEBAO", // 余额宝真实业务动作
        "GOLDEN_BEAN_ZHIMA_LIST:ZHIMA_youxi_dageluosi", // 无可验证的外部游戏完成RPC闭环
        "GOLDEN_BEAN_ZHIMA_LIST:ZHIMA_youxi_zheguanwohenxing", // 无可验证的外部游戏完成RPC闭环
        "GOLDEN_BEAN_ZHIMA_LIST:ZHIMA_youxi_hebuguowoba", // 无可验证的外部游戏完成RPC闭环
        "GOLDEN_BEAN_ZHIMA_LIST:ZHIMA_youxi_wodehuayuanshijie", // 无可验证的外部游戏完成RPC闭环
        "GOLDEN_BEAN_ZHIMA_LIST:ZHIMA_youxi_qingyunjuezhifumo", // 无可验证的外部游戏完成RPC闭环
    )

private val farmDefaultBlacklist =
    setOf(
        // 蚂蚁庄园
        "HEART_DONATION_ADVANCED_FOOD_V2", // 茉莉雪梨卷任务
        "HEART_DONATE", // 爱心捐赠
        "20251118_chouchoulechoukuan2|伸出援手，点亮希望", // 装扮抽抽乐公益捐赠任务
        "innerAction:DONATION", // 装扮抽抽乐公益捐赠任务动作
        "categorizationSecondLevel:Public_Welfare_Behavior", // 装扮抽抽乐公益行为分类
        "categorizationThirdLevel:Public_Welfare_Behavior", // 装扮抽抽乐公益行为分类
        "desc:单笔捐赠", // 装扮抽抽乐捐赠换机会任务描述
        "targetUrl:donationSubject", // 装扮抽抽乐公益捐赠专题页
        "SHANGOU_xiadan", // 逛闪购外卖1元起吃
        "OFFLINE_PAY", // 到店付款
        "ONLINE_PAY", // 线上支付
        "HUABEI_MAP_180", // 用花呗完成一笔支付
        "茉莉雪梨卷任务",
        "爱心捐赠（每天2次）",
        "逛闪购外卖1元起吃",
        "到店付款",
        "线上支付",
    )

private val oceanDefaultBlacklist =
    setOf(
        "BWXRK_QDRW_HAIYANG",
    )

private val forestDefaultBlacklist =
    setOf(
        // 蚂蚁森林
        "ENERGY_XUANJIAO_huanbaobei环保杯",
        "ENERGY_XUANJIAO_zhiyinshui直饮水",
        "ENERGY_XUANJIAO_dianzibaodan电子保单",
        "ENERGY_XUANJIAO_gongjiaochuxing公交出行",
        "ENERGY_XUANJIAO_lvsejiazhuang绿色家装",
        "ENERGY_XUANJIAO_shenghuojiaofei生活缴费",
        "ENERGY_XUANJIAO_lvsezhengwu绿色政务",
        "ENERGY_XUANJIAO_saomagoupiao扫码购票",
        "ENERGY_XUANJIAO_lvseruzhu绿色入住",
        "ENERGY_XUANJIAO_tingchejiaofei停车缴费",
        "ENERGY_XUANJIAO_lvsehuishou绿色回收",
        "ENERGY_XUANJIAO_xinyongzhu信用住",
        "ENERGY_XUANJIAO_wangshangjijian网上寄件",
        "ENERGY_XUANJIAO_gongxiangdanche共享单车",
        "ENERGY_XUANJIAO_gongxiangzulin共享租赁",
        "ENERGY_XUANJIAO_gongxianchongdianbao共享充电宝",
        "ENERGY_XUANJIAO_lvseyinhang绿色银行",
        "ENERGY_XUANJIAO_dianzizhangdan电子账单",
        "ENERGY_XUANJIAO_faxianlvseshenhuo探动植物",
        "ENERGY_XUANJIAO_lvsebaozhuang绿色包装",
        "ENERGY_XUANJIAO_etcjiaofeiETC缴费",
        "ENERGY_XUANJIAO_linqishipin临期食品",
        "ENERGY_XUANJIAO_lvsechexian绿色车险",
        "ENERGY_XUANJIAO_lvsewaimai绿色外卖",
        "ENERGY_XUANJIAO_xinnengyuanzuche新能源租车",
        "ENERGY_XUANJIAO_wuzhihuayuedu无纸化阅读",
        "ENERGY_XUANJIAO_tushujieyue图书借阅",
        "ENERGY_XUANJIAO_cheliangtingshi车辆停驶",
        "ENERGY_XUANJIAO_lvsefeixing绿色飞行",
        "ENERGY_XUANJIAO_dianzixiaopiao电子小票",
        "ENERGY_XUANJIAO_xianshanghuankuan线上还款",
        "ENERGY_XUANJIAO_saomadiandan扫码点单",
        "ENERGY_XUANJIAO_lvseyiliao绿色医疗",
        "ZHRW_AQapp_202512去蚂蚁阿福健康问答",
        "ENERGY_XUANJIAO_xianxiazhifu线下支付",
        "ENERGY_XUANJIAO_dianzifapiao电子发票",
        "ENERGY_XUANJIAO_huanbaojiansu环保减塑",
        "widget_100g_202509添加组件及时收能量",
        "ENERGY_XUANJIAO_wanggouhuochepiao网购火车票",
        "ENERGY_XUANJIAO_xingzou行走",
        "ENERGY_XUANJIAO_wangluogoupiao网络购票",
        "SHARETASK_NEW邀请1位好友助力",
        "ENERGY_XUANJIAO_lvsechangguan绿色场馆",
        "ENERGY_XUANJIAO_daxinnengyuanche打新能源车",
        "ENERGY_XUANJIAO_guojituishui国际退税",
        "ENERGY_XUANJIAO_dianzijiayou电子加油",
        "ENERGY_XUANJIAO_lvsejiadian绿色家电",
        "ENERGY_XUANJIAO_gonggongchongdianzhuang公共充电桩",
        "ENERGY_XUANJIAO_lvsebangong绿色办公",
        "ENERGY_XUANJIAO_ditiechuxing地铁出行",
        "ENERGY_XUANJIAO_guangpanxingdong光盘行动",
        "ENERGY_XUANJIAO_dianzizhifu电子支付",
        "FOREST_CONTINUOUS_COLLECT_ENERGY_7连续7天收自己能量",
        "LSHS_huisho20_202508", // 完成旧衣回收得能量
        "YUSHU_202511", // 单种榆树，年年有榆
        "KTKZ_YS202511", // 一起组团种榆树
        "CNXDY_TASK_QUDONG", // 玩下蛋鸭击败20只怪：不支持rpc调用
        "FOREST_NORMAL_DRAW_YYDWLXQ_ZH", // finishTaskopengreen 返回 400000040
        "FOREST_ACTIVITY_DRAW_ZHXF_ZHWUFU", // finishTaskopengreen 返回 400000040
        "FOREST_NORMAL_DRAW_SHARE", // 森林抽抽乐分享任务
        "FOREST_ACTIVITY_DRAW_SHARE", // 森林抽抽乐活动分享任务
        "ZHRW_HUIYUANjiaoshui_202608", // 每日浇水免费拿绿植：finishTask 返回 400000040
        "MHXCZ_RYCZ_HDCCL|玩梦幻消除战充值任意金额", // 森林抽抽乐充值任务，缺少稳定完成RPC闭环
        "RYCZ", // 森林抽抽乐充值类任务前缀，缺少稳定完成RPC闭环
        "SYH_51HLZ_zhuanhua202604", // 【抢金条】完成游戏任务：不支持rpc调用
        "FKSSJ_QDRW_HUOLI", // 水世界捡海面物资1次：不支持rpc调用
        "FKSSJ_LJRW_HUOLI", // 水世界捡海面物资5次：不支持rpc调用
        "FKSSJ_LJRWtanxian_HUOLI|玩水世界探险船闯5关", // 不支持rpc调用，缺少稳定完成RPC闭环
        "FKSSJ_LJRWdiaoyu_HUOLI", // 水世界手动钓鱼成功10次：不支持rpc调用
        "YBLB_TASK_QUDONG", // 玩一步两步通关1次：不支持rpc调用
        "MHXCZ_TASK_QUDONG|梦幻消除战做1个订单", // 不支持rpc调用，缺少稳定完成RPC闭环
        "XJSKP_TASK_QUDONG|玩向僵尸开炮通关1次", // 400000040 不支持rpc调用，缺少稳定完成RPC闭环
        "GYG_AQapp_202511|去蚂蚁阿福揭秘真相", // 不支持rpc调用，缺少稳定完成RPC闭环
        "BWXRK_TASK_QUDONG|保卫向日葵通过1关", // 不支持rpc调用，缺少稳定完成RPC闭环
        "GYG_TAOBAOzhibo_202606|去淘宝花花乐领红包", // 不支持rpc调用，缺少稳定完成RPC闭环
        "GYG_taobaoqiandao_202603", // TODO 完成闭环未证实；不附带标题以免误挡已验证的 TBQD
        "FOREST_ACTIVITY_DRAW_SQYT_1|去神奇鱼塘投喂动物", // 抽抽乐外部鱼塘任务，缺少稳定完成RPC闭环
        "去神奇鱼塘投喂动物", // 抽抽乐标题兜底，缺少稳定完成RPC闭环
        "开宝箱", // 森林抽抽乐宝箱类任务不在本流程处理
        "抢金条", // 森林抽抽乐游戏类任务暂无稳定RPC闭环
        "去会员抢演唱会门票", // 活动已完结
        "FOREST_PLAYGROUND_RECHARGE", // 森林乐园充值类任务，缺少稳定完成闭环
        "FOREST_PLAYGROUND_GAME_PASS", // 森林乐园通关类任务，缺少稳定完成闭环
        "充值任意金额",
        "通过5关",
    )

private val fishPondDefaultBlacklist =
    setOf(
        // 福气鱼池：游戏、订阅、分享、翻倍广告等任务缺少稳定自动完成闭环
        "FISHPOND_NCLY_GAME",
        "NORMAL_RENMENYOUXI",
        "TASK_SUBSCRIBE",
        "ANTFISHPOND_WECHAT_SHARE",
        "LOTTERY_PLUS",
        "RESCUE_AD",
        "RESULT_DOUBLE_AD",
        "FLOAT_GAME_AD",
    )

private val stallDefaultBlacklist =
    setOf(
        // 蚂蚁新村
        "玩生存33天电台招募10次", // 新村小游戏任务，缺少稳定完成RPC闭环
        "玩保卫向日葵通关1次", // 新村小游戏任务，缺少稳定完成RPC闭环
        "【限时】去玩超级解压馆", // 新村小游戏任务，缺少稳定完成RPC闭环
        "去玩解压小游戏", // 新村小游戏任务，缺少稳定完成RPC闭环
        "ANTSTALL_NORMAL_DAILY_DONATE_COUNT|助力就业岗位",
        "ANTSTALL_TASK_xcjmjyjuankuan2026|帮乡村姐妹家乡就业",
        "ANTSTALL_TASK_kuaishouhuanduan|去快手逛一逛",
        "ANTSTALL_TASK_taojinbihuanduan|进入淘宝芭芭农场领免费水果",
        "ANTSTALL_P2P_DAILY_SHARER|邀请好友助力",
    )

private val yuebaoDefaultBlacklist =
    setOf(
        // 余额宝
        "添加余额宝小组件",
        "让余额宝自动赚更多",
        "去余额宝攒一笔钱",
        "去天天秒杀下1单",
        "攒入一笔余额宝",
        "开通余额宝",
        "余额宝组件",
    )

private val goldTicketDefaultBlacklist = emptySet<String>()

private val dodoDefaultBlacklist =
    setOf(
        "SGBHSD_QDRW", // 三国冰河时代类任务前缀，缺少稳定完成闭环
        "GAME_CSGZJX_202502", // 2026-08-17 外部游戏任务无稳定完成/领奖RPC闭环
        "widget_202604|惊喜任务：添加森林组件", // 缺少稳定完成RPC闭环
    )

private val memberDefaultBlacklist =
    setOf(
        // 会员
        "SYH_RTB_SHOW_TASK_INDEX_1",
        "逛淘宝签到领现金",
        "逛一逛淘宝芭芭农场",
        "逛百度天天领现金",
        "逛一逛快手",
        "玩向往的生活合成30次",
        "玩保卫向日葵通过1关",
        "玩无名之辈消耗20个包子",
        "逛淘宝特价版",
        "玩毛线消不停通过2关",
        "玩会员爱解压通过2关",
        "逛一逛一淘APP",
        "玩造化仙府升级建筑3次",
        "玩浪漫餐厅提交5个订单",
        "玩龙迹之城升级10次英雄",
        "玩螺丝消不停通2关",
        "玩斗罗大陆零击败40只怪物",
        "逛百度极速版领钱",
        "邀请好友签到领积分",
        "玩梦幻消除战完成5个订单",
        "每天逛逛蚂蚁阿福",
        "1分钱起囤奶茶咖啡",
        "玩最强斗王通过3关主线关卡",
        "玩三国冰河时代超历史1w战力",
        "逛一逛大众点评",
        "逛一逛淘金币频道",
        "逛美团刷视频领现金",
        "逛一逛抖音极速版",
        "玩向西冲冲冲升5级",
        "SYH_RTB_SHOW_TASK_INDEX_2|去签名设计", // 304/TASK_NOT_FINISHED
        "SYH_RTB_SHOW_TASK_INDEX_3|玩游戏通过1次游戏", // 001，系统异常
        "AP15353904|玩猪了个猪",
        "2060170000365923#lmct_game_order_every_1#20|玩浪漫餐厅",
        "2021004163668677#wdhysj_game_zhuxian_every_1#12|玩我的世界",
        "2060170000365575#ddxbt_game_pass_every_1#5|玩点点消不停",
        "2021005139650112#dgls_game_level_every_1#4|玩打个螺丝",
        "2021003184609526#pplwg_game_pass_level_every_1#5|玩泡泡龙王国",
        "JYMJFTE_TASK", // 商家真实业务/外跳任务
        "TJZMZJ_TASK",
        "zzsjxyx|玩主宰世界|通过8关主线关卡",
        "yzsc|玩约战沙城|完成5个日常活动",
        "yblb2060170000359285|玩一步两步|通过2关",
        "ljzc|玩龙迹之城|击杀6次挑战boss",
        "sjwy|玩四季物语|完成15个订单",
        "zlgz2060170000375112|玩猪了个猪|通过8关",
        "zcylt|玩这城有良田|举办4次庙会",
        "hlxxx|玩欢乐消消消|完成6个夜市任务",
        "营业执照",
    )

private val insuredDefaultBlacklist =
    setOf(
        "AP1835211|逛一逛冲鸭",
        "AP17356765|查看借呗额度得保障金2元", // 需真实业务行为，缺少稳定完成RPC闭环
        "AP13341733|体验稳稳涨收益好品", // 需真实业务行为，缺少稳定完成RPC闭环
        "AP19282085|0元体验热门健康保障", // 需真实投保行为，缺少稳定完成RPC闭环
        "AP16253999|0元体验最高百万重疾保障", // 需真实投保行为，缺少稳定完成RPC闭环
    )

private val youthPrivilegeDefaultBlacklist =
    setOf(
        "AP17294013",   // “添加到首页”
        "AP10295780", // taskComplete 返回 TASK_OPERATE_ERROR，retryable=false
    )

private val sportsDefaultBlacklist =
    setOf(
        // 运动
        "玩游戏",
        // 签名/设计类广告任务：finishAdTask 返回 304/TASK_NOT_FINISHED
        "AP17300472",
        "AP14300572",
        "AP18300607",
        "AP13300544",
        "AP19300555",
        "AP18300546",
        "AP10300545",
        "AP16300608",
        "AP12300554",
        "AP13300501",
        "AP16300571",
        "AP11300601|逛好运卡翻红包", // 面板入口缺少稳定自动闭环
        "AP10344931|逛好运卡翻红包", // 路线复活入口 completeTask 返回 CAMP_TRIGGER_ERROR
    )

private val myBankWelfareDefaultBlacklist =
    setOf(
        "AP12341521", // 查看借呗额度：需真实授信业务行为
        "AP18353629", // 办理全国大流量卡：需真实办理业务
        "AP12333795", // 完成1笔借呗支用：需真实借款支用行为
    )

val DEFAULT_BLACKLIST: Map<String, Set<String>> =
    mapOf(
        "芝麻信用" to sesameCreditDefaultBlacklist,
        "芝麻炼金" to sesameAlchemyDefaultBlacklist,
        "芭芭农场" to orchardDefaultBlacklist,
        "金豆夺宝" to goldenBeanDefaultBlacklist,
        "蚂蚁庄园" to farmDefaultBlacklist,
        "神奇海洋" to oceanDefaultBlacklist,
        "蚂蚁森林" to forestDefaultBlacklist,
        "余额宝" to yuebaoDefaultBlacklist,
        "黄金票" to goldTicketDefaultBlacklist,
        "会员" to memberDefaultBlacklist,
        "蚂蚁保" to insuredDefaultBlacklist,
        "青春特权" to youthPrivilegeDefaultBlacklist,
        "运动" to sportsDefaultBlacklist,
        "网商银行" to myBankWelfareDefaultBlacklist,
        "神奇物种" to dodoDefaultBlacklist,
        "蚂蚁新村" to stallDefaultBlacklist,
        "福气鱼池" to fishPondDefaultBlacklist,
    )
