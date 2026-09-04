package com.randomspellbench.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量汉字转拼音（零外部依赖，内置常用汉字表），供法术搜索使用。
 *
 * <p>不引第三方库的原因：Forge 运行时不带这些依赖，需 shadow/shade 打进 jar
 * 并在 mods.toml 声明，打包链路复杂且涨体积。搜索只需「常用汉字 → 音节」单向映射。</p>
 *
 * <p>「召唤北极熊」→ 全拼 {@code zhaohuanbeijixiong} / 首字母 {@code zhbxj}。
 * 多音字取表中首次出现的读音；表外汉字回退原字符，不影响其余字匹配。</p>
 */
public final class PinyinUtil {

    /**
     * 拼音表，每项为一组（按首字母归并），格式：{@code 音节:该音节汉字|音节:汉字|...}
     * 静态块解析为 {@code 汉字 -> 音节} 映射。
     */
    private static final String[] DATA = {
            "a:啊阿吖|ai:爱哀挨埃癌矮碍艾唉|an:安暗岸按案鞍氨|ang:昂盎|ao:奥傲熬凹澳懊坳",
            "ba:八巴拔把爸霸罢坝跋|bai:白百摆败拜柏|ban:半班般板搬伴办斑|bang:帮邦棒绑榜磅|bao:宝保报包抱暴薄爆堡|bei:北被备背悲杯碑贝倍|ben:本奔笨|beng:崩蹦|bi:比必笔避闭壁毕碧鼻彼逼|bian:边变便遍编辩鞭扁|biao:表标彪|bie:别|bin:宾滨彬|bing:冰兵并病饼丙|bo:波播博伯薄勃泊舶搏|bu:不布步部补捕哺怖簿",
            "ca:擦|cai:才材财采菜彩裁猜踩|can:参残餐惨灿|cang:藏仓苍|cao:草操曹槽|ce:策测侧册厕|ceng:层曾|cha:查察差插茶叉刹|chai:拆柴|chan:产颤缠禅铲阐|chang:长场常厂唱尝畅肠昌|chao:超朝潮吵抄巢炒|che:车彻撤扯|chen:沉陈晨尘臣称衬趁|cheng:成城程称承诚乘惩橙澄|chi:吃持迟池驰赤翅斥痴齿|chong:冲充虫崇重宠|chou:抽仇愁筹丑臭|chu:出初处除础楚触储厨橱畜|chuan:传船川穿串喘|chuang:创床窗闯疮|chui:吹垂锤炊|chun:春纯唇蠢|chuo:戳|ci:次此词刺磁慈瓷辞茨|cong:从丛聪葱|cu:粗促醋簇|cuan:窜|cui:催摧翠脆粹|cun:存村寸|cuo:错措挫搓",
            "da:大打达答搭瘩|dai:代带待戴袋贷呆怠逮|dan:单但淡担丹弹蛋胆诞|dang:当党挡档荡|dao:到道倒岛导刀盗稻悼|de:的得德|deng:等登灯邓瞪|di:地第低敌底抵滴迪帝递堤笛|dian:点电店典殿淀垫癫|diao:掉调钓雕吊|die:跌爹叠蝶|ding:定顶订丁钉鼎|diu:丢|dong:动东懂冬洞董栋冻|dou:都斗豆抖陡逗|du:度都独毒读渡督堵赌杜肚|duan:段断短端锻|dui:对队堆兑|dun:顿盾吨蹲敦|duo:多度朵躲夺舵堕",
            "e:饿恶额俄鹅厄遏|en:恩|er:而二儿耳尔饵",
            "fa:发法罚乏伐筏阀|fan:反饭范凡繁烦犯翻番贩泛|fang:方放房防访纺仿妨芳|fei:非飞费废肥沸肺匪菲|fen:分份粉奋纷愤氛坟焚|feng:风封疯峰丰锋蜂逢缝冯凤|fo:佛|fou:否|fu:服福复父副富负夫扶府浮符腐伏抚辅傅腹赴缚",
            "gai:改该盖概钙|gan:干感敢赶甘肝杆尴|gang:刚钢岗港缸冈|gao:高告膏糕搞稿|ge:个格歌哥革隔割阁戈鸽|gei:给|gen:跟根|geng:更耕耿|gong:工公共功供宫弓攻恭巩贡汞|gou:够狗构购勾沟钩|gu:古故固鼓谷骨顾孤姑雇股菇|gua:挂瓜刮寡|guai:怪拐乖|guan:关管观官惯冠馆罐灌贯|guang:光广逛|gui:规贵归鬼柜跪桂硅轨|gun:滚棍|guo:国过果锅裹郭",
            "hai:还海害孩亥|han:汉寒喊含汗韩函旱罕捍|hang:行航杭|hao:好号毫豪浩耗郝|he:和何合河喝盒核贺荷赫|hei:黑嘿|hen:很狠恨痕|heng:横恒衡|hong:红洪宏轰虹鸿哄|hou:后厚候侯喉猴|hu:湖户呼虎护忽胡壶蝴狐糊|hua:化花画话华划滑|huai:坏怀淮|huan:换还欢环患缓幻唤焕|huang:黄荒慌皇煌晃谎|hui:会回挥灰恢惠汇辉毁悔慧|hun:混昏魂浑|huo:火或活获货惑伙霍",
            "ji:机基记级几及集击急计既即季吉纪寄疾迹继奇济籍稽饥肌姬脊极积籍藉疾嫉集辑|jia:家加价假架甲佳嫁夹驾稼|jian:见件间建减检简尖剑坚监健渐箭肩兼艰键荐|jiang:将江强讲降奖姜蒋浆疆|jiao:教交叫脚角较觉焦骄郊搅缴椒|jie:接结解界街节介戒届借杰洁截竭姐|jin:进近今金紧仅尽津禁锦谨浸|jing:经京精境静警竟竞景惊净井敬镜晶|jiu:就究九酒久旧救舅纠|ju:局据举具居剧句巨距聚拒俱菊拘橘|juan:卷捐娟|jue:决绝觉角掘爵|jun:军均君菌俊峻",
            "ka:卡咖|kai:开凯慨|kan:看刊砍堪勘|kang:康抗扛炕|kao:考靠烤拷|ke:可克科客刻颗课渴壳咳|ken:肯恳垦|keng:坑|kong:空控孔恐|kou:口扣寇|ku:苦哭库枯裤酷窟|kua:跨夸垮|kuai:快块筷|kuan:宽款|kuang:况狂矿框旷|kui:亏愧葵魁|kun:困昆|kuo:扩括阔",
            "la:拉啦辣蜡腊|lai:来赖莱|lan:蓝览兰烂拦栏懒滥|lang:狼郎朗浪廊|lao:老劳牢捞姥涝|le:了乐勒|lei:类累雷泪垒蕾|leng:冷愣|li:里理立力例离历李礼丽利厉励梨璃粒隶莉|lian:连联练脸恋怜莲廉链|liang:两量良亮辆梁凉粮谅|liao:了料聊疗辽廖|lie:列烈裂猎劣|lin:林临邻淋磷鳞|ling:领令另灵零龄铃陵玲凌翎|liu:六流留刘柳硫|long:龙隆笼聋垄|lou:楼漏搂|lu:路陆录鹿卢鲁炉露禄|lv:绿旅律滤履|luan:乱卵|lue:略|lun:论轮伦|luo:落罗络洛骆裸螺",
            "ma:马吗妈码骂麻|mai:买卖麦迈埋脉|man:满慢漫曼|mang:忙盲茫芒|mao:毛冒帽貌矛茅猫贸|me:么|mei:没每美妹梅媒眉媚|men:门们闷|meng:梦孟猛蒙盟朦|mi:米密迷秘蜜谜弥|mian:面免棉眠绵勉|miao:秒苗妙庙描瞄|mie:灭蔑|min:民敏闽|ming:名明命鸣铭冥|miu:谬|mo:摸模末莫墨磨默魔沫陌|mou:某谋|mu:木目母墓幕牧穆慕",
            "na:那拿哪纳娜|nai:乃奶耐奈|nan:男南难|nao:脑闹恼|ne:呢|nei:内|neng:能|ni:你尼呢泥逆拟腻|nian:年念碾|niang:娘|niao:鸟尿|nie:捏|nin:您|ning:宁凝|niu:牛扭纽|nong:农弄浓|nu:努怒奴|nv:女|nuan:暖|nuo:诺挪",
            "ou:欧偶呕",
            "pa:怕爬帕趴|pai:派排牌拍徘|pan:判断盘盼叛攀|pang:旁胖庞|pao:跑抛炮泡袍|pei:培配赔陪|pen:喷盆|peng:朋碰棚彭膨鹏|pi:皮批披疲脾匹劈|pian:片篇偏骗|piao:票飘漂|pin:品贫拼频聘|ping:平评凭瓶苹屏|po:破坡迫泼颇婆|pou:剖|pu:普铺扑朴谱浦",
            "qi:其起期七气器企齐奇妻骑棋旗岐祈岂启|qia:恰卡|qian:前千钱签浅牵迁欠潜谦铅|qiang:强抢枪墙腔|qiao:桥巧敲乔瞧悄|qie:切且窃|qin:亲勤秦琴侵禽|qing:情青请轻清庆倾卿|qiong:穷琼|qiu:求球秋丘囚|qu:去区取曲趣驱渠屈|quan:全权劝圈泉犬拳|que:确却缺雀|qun:群",
            "ran:然燃染|rang:让嚷|rao:绕饶扰|re:热惹|ren:人认任忍仁刃|reng:仍扔|ri:日|rong:容荣融绒熔|rou:肉柔|ru:如入乳辱|ruan:软|rui:锐瑞|run:润|ruo:若弱",
            "sa:撒洒|sai:赛塞|san:三散伞|sang:桑丧嗓|sao:扫骚嫂|se:色涩|sen:森|sha:沙杀傻纱刹|shai:晒|shan:山善闪衫扇删珊|shang:上商伤尚赏|shao:少烧稍绍勺哨|she:设社舍射蛇涉摄|shen:深身神申审甚伸慎渗|sheng:生声升胜省圣盛剩牲|shi:是时事实使式十市师石始识史示士世视失施湿诗尸狮适释饰|shou:收手受首守授瘦兽寿|shu:数书树属输束熟述术舒殊蔬淑|shua:刷耍|shuai:帅摔衰甩|shuan:拴|shuang:双爽霜|shui:水谁睡税|shun:顺瞬|shuo:说硕|si:四死思私司丝斯撕似寺饲|song:送松宋耸颂|sou:搜艘|su:素速苏诉宿俗塑肃|suan:算酸|sui:随虽岁碎遂隋髓|sun:孙损笋|suo:所索缩锁琐",
            "ta:他她它塔踏塌|tai:太台态抬泰胎|tan:谈探弹坛炭贪滩摊坦|tang:唐汤堂糖躺趟烫|tao:讨套桃逃陶涛淘|te:特|teng:疼腾藤|ti:提题体替梯踢蹄|tian:天田添甜填|tiao:条调跳挑|tie:铁贴帖|ting:听停庭挺厅亭艇|tong:同通统痛童铜筒桶捅|tou:头投偷透|tu:土突图途徒吐兔屠|tuan:团|tui:推退腿|tun:吞屯|tuo:脱托拖妥拓",
            "wa:挖娃瓦袜蛙|wai:外歪|wan:完万晚湾玩碗弯挽|wang:王往忘网望亡旺汪|wei:为位未委威胃维卫谓围违唯伟尾伪喂慰魏危微|wen:文问闻温稳纹吻|weng:翁|wo:我握窝卧沃|wu:五物无武务误屋污巫午舞吴雾悟勿戊侮",
            "xi:西系希习细吸洗席喜戏析溪悉夕锡熄稀袭|xia:下夏吓峡霞侠狭虾瞎|xian:现先线显险限鲜县献闲嫌陷仙贤弦|xiang:相想向象项响乡香详箱享祥翔橡|xiao:小笑消息效销晓孝硝啸萧|xie:些写谢协血斜携鞋歇泄屑|xin:心新信辛欣薪芯|xing:行性形星型醒幸姓刑邢|xiong:雄兄熊凶胸|xiu:修休秀绣袖锈|xu:需须续许徐序虚蓄|xuan:选宣悬旋玄轩|xue:学血雪穴削靴|xun:寻讯训循巡询迅",
            "ya:压呀牙亚芽鸭雅崖哑|yan:眼验研演言严烟延盐岩颜炎燕宴焰淹|yang:样养阳洋央羊杨扬氧仰痒|yao:要药摇腰邀遥咬耀窑|ye:也业夜叶野爷液页|yi:一以已意义医衣议移易依艺异益遗仪宜疑抑翼疫忆亿|yin:因引印银饮音阴隐吟|ying:应影英营硬迎赢盈鹰婴颖|yo:哟|yong:用永勇拥涌庸咏|you:有由又右游油优友尤幽忧邮|yu:于与语玉遇育预余鱼雨域欲愈羽宇御裕娱愚渔榆|yuan:元原院远愿园圆源员缘援怨|yue:月约越阅跃岳|yun:运云允孕晕蕴韵",
            "za:杂砸|zai:在再灾栽载宰|zan:咱赞暂|zang:脏藏葬|zao:早造遭糟燥枣澡|ze:则责择泽|zei:贼|zen:怎|zeng:增赠曾|zha:扎炸渣闸眨榨|zhai:摘宅窄债|zhan:战占展站沾粘斩盏|zhang:长张章掌涨帐仗障账|zhao:找照招朝召赵兆罩爪|zhe:这着者折哲浙遮|zhen:真镇震针珍振阵诊枕|zheng:正政整证争征郑挣蒸睁|zhi:之只知直支制指值志职治质致执织止纸置秩智殖汁枝脂|zhong:中重种众终钟忠肿仲衷|zhou:周州洲舟粥皱昼宙咒|zhu:主住助注著朱竹逐珠猪诸株烛煮嘱驻筑铸|zhua:抓|zhuan:转专砖传赚|zhuang:装状庄壮撞|zhui:追坠|zhun:准|zhuo:桌捉卓拙酌|zi:子自字资紫姿咨滋仔|zong:总宗纵踪综|zou:走奏揍|zu:组足族祖阻租|zuan:钻|zui:最罪嘴醉|zun:尊遵|zuo:做作左座坐佐"
    };

    /** 汉字 → 音节（putIfAbsent：多音字取表中第一个读音）。 */
    private static final Map<Character, String> PINYIN = new HashMap<>(4096);

    static {
        for (String group : DATA) {
            for (String pair : group.split("\\|")) {
                int sep = pair.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                String syllable = pair.substring(0, sep);
                String chars = pair.substring(sep + 1);
                for (int i = 0; i < chars.length(); i++) {
                    PINYIN.putIfAbsent(chars.charAt(i), syllable);
                }
            }
        }
    }

    private PinyinUtil() {
    }

    /**
     * 生成搜索键：原文小写 + 全拼连写 + 全拼空格 + 首字母，空格分隔。
     * 例：「召唤北极熊」→ {@code 召唤北极熊 zhaohuanbeijixiong zhao huan bei ji xiong zhbxj}
     *
     * <p>纯非中文（英文 id 等）只返回小写原文，不产生冗余副本。</p>
     */
    public static String searchKey(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder full = new StringBuilder();
        StringBuilder spaced = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        boolean hasChinese = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String py = PINYIN.get(c);
            char lowerC = Character.toLowerCase(c);
            if (py != null) {
                hasChinese = true;
                full.append(py);
                if (spaced.length() > 0) {
                    spaced.append(' ');
                }
                spaced.append(py);
                initials.append(py.charAt(0));
            } else {
                // 非汉字（英文/数字/符号）原样保留，保证中英混排也能搜
                full.append(lowerC);
                spaced.append(lowerC);
                initials.append(lowerC);
            }
        }
        if (!hasChinese) {
            return lower;
        }
        return lower + " " + full + " " + spaced + " " + initials;
    }
}
