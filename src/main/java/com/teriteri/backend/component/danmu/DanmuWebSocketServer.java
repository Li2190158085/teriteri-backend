package com.teriteri.backend.component.danmu;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.teriteri.backend.mapper.DanmuMapper;
import com.teriteri.backend.mapper.VideoStatsMapper;
import com.teriteri.backend.pojo.Danmu;
import com.teriteri.backend.pojo.User;
import com.teriteri.backend.pojo.VideoStats;
import com.teriteri.backend.utils.JwtUtil;
import com.teriteri.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ServerEndpoint(value = "/ws/danmu/{vid}/{uuid}")
public class DanmuWebSocketServer {

    // 由于每个连接都不是共享一个WebSocketServer，所以要静态注入
    private static JwtUtil jwtUtil;
    private static RedisUtil redisUtil;
    private static DanmuMapper danmuMapper;
    private static VideoStatsMapper videoStatsMapper;

    @Autowired
    public void setDependencies(JwtUtil jwtUtil, RedisUtil redisUtil, DanmuMapper danmuMapper, VideoStatsMapper videoStatsMapper) {
        DanmuWebSocketServer.jwtUtil = jwtUtil;
        DanmuWebSocketServer.redisUtil = redisUtil;
        DanmuWebSocketServer.danmuMapper = danmuMapper;
        DanmuWebSocketServer.videoStatsMapper = videoStatsMapper;
    }

    // 对每个视频存储该视频下的sessionId集合
    private static final Map<String, Set<String>> videoConnectionMap = new ConcurrentHashMap<>();

    // 存储每个sessionId对应的session对象
    private static final Map<String, Session> danmuSessionMap = new ConcurrentHashMap<>();

    /**
     * 连接建立时触发，记录session到map
     * @param session 会话
     * @param vid   视频的ID
     * @param uuid  随机uuid，由前端生成，作为每个会话的唯一标识符
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("vid") String vid, @PathParam("uuid") String uuid) {
        if (videoConnectionMap.get(vid) == null) {
            Set<String> set = new HashSet<>();
            set.add(uuid);
            videoConnectionMap.put(vid, set);
        } else {
            videoConnectionMap.get(vid).add(uuid);
        }
        danmuSessionMap.put(uuid, session);
        sendMessage(vid, "当前观看人数" + videoConnectionMap.get(vid).size());
//        System.out.println("建立连接，当前观看人数: " + videoConnectionMap.get(vid).size());
    }

    /**
     * 收到消息时触发，记录到数据库并转发到对应的全部连接
     * @param session   当前会话
     * @param message   信息体（包含"token"、"vid"、"data"字段）
     * @param vid   视频ID
     */
    @OnMessage
    public void onMessage(Session session, String message, @PathParam("vid") String vid) {
        try {
            JSONObject msg = JSON.parseObject(message);

            // token鉴权
            String token = msg.getString("token");
            if (!StringUtils.hasText(token) || !token.startsWith("Bearer ")) {
                session.getBasicRemote().sendText("登录已过期");
                return;
            }
            token = token.substring(7);
            boolean verifyToken = jwtUtil.verifyToken(token);
            if (!verifyToken) {
                session.getBasicRemote().sendText("登录已过期");
                return;
            }
            String userId = JwtUtil.getSubjectFromToken(token);
            String role = JwtUtil.getClaimFromToken(token, "role");
            User user = redisUtil.getObject("security:" + role + ":" + userId, User.class);
            if (user == null) {
                session.getBasicRemote().sendText("登录已过期");
                return;
            }

            // 写库
            JSONObject data = msg.getJSONObject("data");
//            System.out.println(data);
            Danmu danmu = new Danmu(
                    null,
                    Integer.parseInt(vid),
                    user.getUid(),
                    data.getString("content"),
                    data.getInteger("fontsize"),
                    data.getInteger("mode"),
                    data.getString("color"),
                    data.getDouble("timePoint"),
                    1,
                    new Date()
            );
            danmuMapper.insert(danmu);
            UpdateWrapper<VideoStats> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("vid", Integer.parseInt(vid)).setSql("danmu = danmu + 1");
            videoStatsMapper.update(new VideoStats(), updateWrapper);
            redisUtil.addMember("danmu_idset:" + vid, danmu.getId());   // 加入对应视频的ID集合，以便查询
            redisUtil.delValue("videoStats:" + vid);    // 删除redis上的状态缓存

            // 广播弹幕
            String dmJson = JSON.toJSONString(danmu);
            sendMessage(vid, dmJson);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 连接关闭时执行
     * @param vid   视频ID
     * @param uuid  随机uuid，由前端生成，作为每个会话的唯一标识符
     */
    @OnClose
    public void onClose(@PathParam("vid") String vid, @PathParam("uuid") String uuid) {
        // 从缓存中移除连接记录
        videoConnectionMap.get(vid).remove(uuid);
        if (videoConnectionMap.get(vid).size() == 0) videoConnectionMap.remove(vid);
        danmuSessionMap.remove(uuid);
        // 更新在线人数
        sendMessage(vid, "当前观看人数" + videoConnectionMap.get(vid).size());
//        System.out.println("关闭连接，当前观看人数: " + videoConnectionMap.get(vid).size());
    }

    @OnError
    public void onError(Throwable error) {
        log.error("websocket发生错误");
        error.printStackTrace();
    }

    /**
     * 往对应的全部连接发送消息
     * @param vid   视频ID
     * @param text  消息内容，对象需转成JSON字符串
     */
    public void sendMessage(String vid, String text) {
        Set<String> set = videoConnectionMap.get(vid);
        for (String sessionId : set) {
            Session session = danmuSessionMap.get(sessionId);
            try {
                session.getBasicRemote().sendText(text);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
