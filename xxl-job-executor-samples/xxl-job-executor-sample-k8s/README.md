# XXL-JOB Executor Sample for Kubernetes

这是一个专为 Kubernetes 环境设计的 XXL-JOB 执行器示例项目。

## 功能特性

- ✅ 运行在 Kubernetes 集群中
- ✅ 使用 ServiceAccount 访问 K8s API（无需 kubeconfig）
- ✅ 支持 K8s Job 类型任务执行
- ✅ 自动获取 Deployment 镜像配置
- ✅ 实时收集 Pod 日志

## 构建镜像

```bash
# 构建 Docker 镜像
docker build -t xxl-job-executor-k8s:latest .

# 或者使用脚本构建（如果在 kind 环境）
docker build -t xxl-job-executor-k8s:latest .
kind load docker-image xxl-job-executor-k8s:latest
```

## 部署到 Kubernetes

### 1. 创建 ServiceAccount 和 RBAC

```bash
kubectl apply -f k8s/serviceaccount.yaml
```

### 2. 部署执行器

```bash
kubectl apply -f k8s/deployment.yaml
```

### 3. 验证部署

```bash
# 检查 Pod 状态
kubectl get pods -l app=xxl-job-executor-k8s

# 查看日志
kubectl logs -f deployment/xxl-job-executor-k8s
```

## 配置说明

### 环境变量

可以在 `k8s/deployment.yaml` 中配置以下环境变量：

- `XXL_JOB_ADMIN_ADDRESSES`: XXL-JOB Admin 地址
- `XXL_JOB_EXECUTOR_APPNAME`: 执行器名称（必须唯一）
- `XXL_JOB_EXECUTOR_PORT`: 执行器端口
- `XXL_JOB_ACCESS_TOKEN`: 访问令牌

### RBAC 权限

ServiceAccount `xxl-job-executor` 需要以下权限：

- **Deployments**: `get`, `list` - 读取 Deployment 获取镜像配置
- **Jobs**: `create`, `get`, `list`, `watch`, `delete` - 管理 K8s Jobs
- **Pods/Logs**: `get`, `list`, `watch` - 读取 Pod 日志

## 使用 K8s Job 任务

### 1. 在 XXL-JOB Admin 中创建任务

- **运行模式**: 选择 `GLUE(K8sJob)`
- **执行器**: 选择 `xxl-job-executor-k8s`

### 2. 配置任务参数（JSON 格式）

```json
{
  "deployment": "your-app-deployment",
  "namespace": "default",
  "command": ["python3", "batch_job.py"],
  "args": ["--date", "${jobParam}", "--shard", "${shardIndex}/${shardTotal}"],
  "ttlSecondsAfterFinished": 3600,
  "backoffLimit": 3
}
```

### 参数说明

- `deployment`: 源 Deployment 名称，用于获取镜像
- `namespace`: Kubernetes 命名空间
- `command`: 执行命令（可选，覆盖容器默认命令）
- `args`: 命令参数，支持 XXL-JOB 变量：
  - `${jobParam}` - 任务参数
  - `${shardIndex}` - 分片索引
  - `${shardTotal}` - 分片总数
- `ttlSecondsAfterFinished`: Job 完成后保留时间（秒）
- `backoffLimit`: 失败重试次数

### 3. 执行流程

1. 执行器从指定 Deployment 获取容器镜像和环境变量
2. 创建 Kubernetes Job
3. 监控 Job 执行状态
4. 收集 Pod 日志并输出到 XXL-JOB 日志
5. Job 完成后根据 TTL 自动清理

## 本地开发

```bash
# 编译项目
mvn clean package

# 运行（需要配置 Kubernetes 访问权限）
java -jar target/xxl-job-executor-sample-k8s-*.jar
```

## 故障排查

### 执行器无法连接到 Admin

检查 Service 配置：
```bash
kubectl get svc xxl-job-admin
```

### 无法创建 K8s Job

检查 ServiceAccount 权限：
```bash
kubectl auth can-i create jobs --as=system:serviceaccount:default:xxl-job-executor
kubectl auth can-i get deployments --as=system:serviceaccount:default:xxl-job-executor
```

### 查看执行器日志

```bash
kubectl logs -f deployment/xxl-job-executor-k8s
```

## 注意事项

- 执行器必须运行在有 K8s Job 任务的相同集群中
- 确保 ServiceAccount 有足够的权限访问 Deployments 和创建 Jobs
- Job 日志会实时输出到 XXL-JOB 日志系统
- 建议配置合适的 `ttlSecondsAfterFinished` 以自动清理完成的 Jobs

## 更多信息

- [XXL-JOB 官方文档](https://www.xuxueli.com/xxl-job/)
- [Kubernetes Jobs 文档](https://kubernetes.io/docs/concepts/workloads/controllers/job/)
