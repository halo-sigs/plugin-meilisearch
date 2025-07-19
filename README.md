# Halo Meilisearch 搜索引擎插件

为 Halo 博客系统提供 Meilisearch 搜索引擎集成，支持全文搜索、实时索引更新和高级搜索功能。

## 功能特性

- 🚀 快速全文搜索
- 📝 实时索引更新
- 🎯 高精度搜索结果
- 🔧 灵活的配置选项
- 📱 支持移动端搜索
- 🌐 多语言搜索支持

## 安装配置

### 1. 安装插件

将插件文件上传到 Halo 系统并启用。

### 2. 配置 Meilisearch 服务

确保您已经运行了 Meilisearch 服务实例。如果没有，可以通过以下方式快速启动：

```bash
# 使用 Docker 运行
docker run -it --rm \
  -p 7700:7700 \
  -e MEILI_ENV='development' \
  -v $(pwd)/meili_data:/meili_data \
  getmeili/meilisearch:latest

# 或者下载二进制文件运行
curl -L https://install.meilisearch.com | sh
./meilisearch --master-key="your-master-key"
```

### 3. 插件配置

在 Halo 后台的插件设置中配置以下参数：

- **Meilisearch Host URL**: Meilisearch 服务地址（如：`http://localhost:7700`）
- **Meilisearch Master Key**: Meilisearch 主密钥
- **Index Name**: 索引名称（默认：`halo`）

### 4. 启用搜索引擎

在 Halo 系统设置的搜索引擎选项中选择 "Meilisearch"。

## 使用说明

配置完成后，插件将自动：

1. 创建搜索索引
2. 同步现有内容到 Meilisearch
3. 实时更新内容变更
4. 提供高质量的搜索结果

## 搜索特性

- **全文搜索**: 支持标题、内容、描述的全文检索
- **实时过滤**: 自动过滤未发布、回收站、私密内容
- **高亮显示**: 搜索关键词高亮显示
- **相关性排序**: 智能相关性算法排序
- **快速响应**: 毫秒级搜索响应

## 技术实现

- 基于 Meilisearch Java SDK 0.12.0
- 支持 Halo 2.21.0+
- 异步索引更新
- 配置热更新

## 故障排除

### 连接问题
- 检查 Meilisearch 服务是否正常运行
- 验证 Host URL 和 Master Key 配置
- 确认网络连接正常

### 搜索结果异常
- 检查索引是否创建成功
- 验证内容是否正确同步
- 查看插件日志获取详细信息

## 开发相关

本插件参考了 Algolia 搜索引擎插件的设计模式，重新实现了 Meilisearch 的集成逻辑，提供了更稳定和高效的搜索体验。

### 主要改进

1. 简化配置流程，直接在表单中配置连接信息
2. 优化文档索引策略，使用 metadataName 作为主键
3. 改进错误处理和日志记录
4. 增强搜索结果的相关性和准确性

## 许可证

GPL-3.0 License 