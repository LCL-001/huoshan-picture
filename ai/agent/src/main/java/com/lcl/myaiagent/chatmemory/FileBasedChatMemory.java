package com.lcl.myaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于文件持久化的对话记忆实现
 * 使用Kryo序列化框架将聊天消息存储到本地文件中，每个会话对应一个独立的.kryo文件
 */
public class FileBasedChatMemory implements ChatMemory {

    private final String BASE_DIR;
    private static final Kryo kryo = new Kryo();

    static {
        kryo.setRegistrationRequired(false);
        // 设置实例化策略
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    /**
     * 构造基于文件的聊天记忆实例
     * 自动创建指定的存储目录（如果不存在）
     *
     * @param dir 聊天消息文件的存储目录路径
     */
    public FileBasedChatMemory(String dir) {
        this.BASE_DIR = dir;
        File baseDir = new File(dir);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
    }

    /**
     * 添加消息到指定会话的聊天记录中
     * 先读取现有关于话记录，追加新消息后重新保存到文件
     *
     * @param conversationId 会话唯一标识
     * @param messages       需要添加的消息列表
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        conversationMessages.addAll(messages);
        saveConversation(conversationId, conversationMessages);
    }

    /**
     * 获取指定会话的所有历史聊天记录
     * 如果会话不存在则返回空列表
     *
     * @param conversationId 会话唯一标识
     * @return List<Message> 该会话的所有历史消息列表
     */
    @Override
    public List<Message> get(String conversationId) {
        return getOrCreateConversation(conversationId);
    }

    /**
     * 获取指定会话最近N条历史聊天记录
     * 从所有消息中跳过前面的消息，只返回最后lastN条
     *
     * @param conversationId 会话唯一标识
     * @param lastN          需要返回的最近消息数量
     * @return List<Message> 最近的N条消息列表，如果总消息数不足N则返回全部
     */
    public List<Message> get(String conversationId, int lastN) {
        List<Message> allMessages = getOrCreateConversation(conversationId);
        return allMessages.stream()
                .skip(Math.max(0, allMessages.size() - lastN))
                .toList();
    }

    /**
     * 清空指定会话的所有聊天记录
     * 直接删除对应的.kryo文件
     *
     * @param conversationId 会话唯一标识
     */
    @Override
    public void clear(String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * 读取或创建指定会话的消息列表
     * 如果会话文件存在则从文件中反序列化加载，否则返回空列表
     *
     * @param conversationId 会话唯一标识
     * @return List<Message> 该会话的消息列表，文件不存在时返回空列表
     */
    private List<Message> getOrCreateConversation(String conversationId) {
        File file = getConversationFile(conversationId);
        List<Message> messages = new ArrayList<>();
        if (file.exists()) {
            try (Input input = new Input(new FileInputStream(file))) {
                messages = kryo.readObject(input, ArrayList.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return messages;
    }

    /**
     * 保存指定会话的消息列表到文件
     * 使用Kryo序列化将消息列表写入对应的.kryo文件
     *
     * @param conversationId 会话唯一标识
     * @param messages       需要保存的消息列表
     */
    private void saveConversation(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);
        try (Output output = new Output(new FileOutputStream(file))) {
            kryo.writeObject(output, messages);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取指定会话对应的文件对象
     * 文件命名规则为：{conversationId}.kryo
     *
     * @param conversationId 会话唯一标识
     * @return File 会话对应的文件对象
     */
    private File getConversationFile(String conversationId) {
        return new File(BASE_DIR, conversationId + ".kryo");
    }
}
