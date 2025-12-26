# Hướng dẫn xem History trong Camunda Community Edition

## Vấn đề
Trong Camunda Community Edition, khi vào chi tiết process instance trong Cockpit, bạn chỉ thấy chế độ **Runtime** mặc định. Tab **History** chỉ có trong Enterprise Edition.

## Giải pháp

### 1. Sử dụng REST API có sẵn

#### Xem process instance history:
```bash
curl http://localhost:8080/engine-rest/history/process-instance/{processInstanceId}
```

#### Xem activity history:
```bash
curl http://localhost:8080/engine-rest/history/activity-instance?processInstanceId={processInstanceId}
```

#### Xem task history:
```bash
curl http://localhost:8080/engine-rest/history/task?processInstanceId={processInstanceId}
```

#### Xem variable history:
```bash
curl http://localhost:8080/engine-rest/history/variable-instance?processInstanceId={processInstanceId}
```

#### Xem tất cả process instances đã hoàn thành:
```bash
curl http://localhost:8080/engine-rest/history/process-instance?finished=true
```

---

### 2. Sử dụng Custom REST API (Đã tạo sẵn)

Tôi đã tạo sẵn `HistoryController` với các endpoints sau:

#### GET `/api/history/process-instance/{processInstanceId}`
Lấy thông tin chi tiết process instance

#### GET `/api/history/process-instance/{processInstanceId}/activities`
Lấy danh sách activities đã thực thi

#### GET `/api/history/process-instance/{processInstanceId}/tasks`
Lấy danh sách tasks

#### GET `/api/history/process-instance/{processInstanceId}/variables`
Lấy danh sách variables

#### GET `/api/history/finished-processes`
Lấy tất cả process instances đã hoàn thành

Query parameters:
- `processDefinitionKey` (optional): Lọc theo process definition

#### GET `/api/history/running-processes`
Lấy tất cả process instances đang chạy

Query parameters:
- `processDefinitionKey` (optional): Lọc theo process definition

---

### 3. Sử dụng Web UI (Đã tạo sẵn)

#### A. Process History Viewer
Truy cập: **http://localhost:8080/process-history.html**

Tính năng:
- ✅ Tìm kiếm process instance theo ID
- ✅ Xem tất cả finished processes
- ✅ Xem tất cả running processes
- ✅ Hiển thị chi tiết:
  - Process information
  - Activities timeline
  - Tasks history
  - Variables

#### B. Operation Log & Audit Trail UI ⭐ MỚI
Truy cập: **http://localhost:8080/audit-trail.html**

Tính năng nâng cao:
- 📋 **Operation Log Tab**:
  - ✅ Xem tất cả operations (Create, Update, Delete, Complete, Claim, etc.)
  - ✅ Filter theo User ID, Process Instance ID, Operation Type, Entity Type
  - ✅ Filter theo khoảng thời gian (From/To Date)
  - ✅ Hiển thị thay đổi giá trị (Old Value → New Value)
  - ✅ Statistics: Total operations, Today's operations, Unique users
  - ✅ Export to CSV
  - ✅ View operation details

- 🔐 **Audit Trail Tab**:
  - ✅ Xem chi tiết activity instances history
  - ✅ Filter theo Process Instance, Process Definition, Activity Type
  - ✅ Hiển thị timeline của các activities
  - ✅ Statistics: Total activities, Completed, Active, Average duration
  - ✅ Real-time status tracking
  - ✅ Export to CSV

- 📊 **Process History Tab**:
  - ✅ Search by Process Instance ID hoặc Process Definition Key
  - ✅ Filter theo status (Finished/Running/All)
  - ✅ View detailed process information
  - ✅ Quick access to full process details

---

### 4. Query trực tiếp Database

```sql
-- Process instance history
SELECT 
    id_, 
    proc_def_key_, 
    start_time_, 
    end_time_, 
    duration_, 
    state_,
    delete_reason_
FROM act_hi_procinst 
WHERE id_ = 'your-process-instance-id';

-- Activity history
SELECT 
    act_name_,
    act_type_,
    start_time_,
    end_time_,
    duration_
FROM act_hi_actinst 
WHERE proc_inst_id_ = 'your-process-instance-id'
ORDER BY start_time_ DESC;

-- Task history
SELECT 
    name_,
    assignee_,
    start_time_,
    end_time_,
    duration_
FROM act_hi_taskinst 
WHERE proc_inst_id_ = 'your-process-instance-id'
ORDER BY start_time_ DESC;

-- Variable history
SELECT 
    name_,
    var_type_,
    text_,
    long_,
    double_,
    create_time_
FROM act_hi_varinst 
WHERE proc_inst_id_ = 'your-process-instance-id';

-- Operation logs (audit trail)
SELECT 
    user_id_,
    operation_type_,
    entity_type_,
    timestamp_,
    property_,
    org_value_,
    new_value_
FROM act_hi_op_log
WHERE proc_inst_id_ = 'your-process-instance-id'
ORDER BY timestamp_ DESC;
```

---

## Cách sử dụng Web UI

### A. Process History Viewer (http://localhost:8080/process-history.html)

1. **Khởi động ứng dụng**:
   ```bash
   mvn spring-boot:run
   ```

2. **Truy cập**: http://localhost:8080/process-history.html

3. **Tìm process instance**:
   - Nhập Process Instance ID
   - Click "Load History"

4. **Xem danh sách**:
   - Click "Show All Finished" để xem processes đã hoàn thành
   - Click "Show All Running" để xem processes đang chạy

5. **Xem chi tiết**:
   - Click "View Details" ở hàng tương ứng

---

### B. Operation Log & Audit Trail UI (http://localhost:8080/audit-trail.html) ⭐

1. **Khởi động ứng dụng** (nếu chưa):
   ```bash
   mvn spring-boot:run
   ```

2. **Truy cập**: http://localhost:8080/audit-trail.html

3. **Tab Operation Log** 📋:
   - **Filter operations**:
     - Nhập Process Instance ID (optional)
     - Chọn User ID (optional)
     - Chọn Operation Type (Create, Update, Delete, etc.)
     - Chọn Entity Type (ProcessInstance, Task, Variable, etc.)
     - Chọn khoảng thời gian (From Date - To Date)
   - Click **🔍 Search** để tìm kiếm
   - Click **🔄 Clear Filters** để xóa bộ lọc
   - Click **📥 Export CSV** để xuất dữ liệu
   - **Xem statistics**: 
     - Total Operations
     - Today's Operations
     - Unique Users
     - Failed Operations
   - Click **Details** trên mỗi operation để xem chi tiết thay đổi

4. **Tab Audit Trail** 🔐:
   - **Filter activities**:
     - Nhập Process Instance ID (optional)
     - Nhập Process Definition Key (optional)
     - Chọn Activity Type (userTask, serviceTask, etc.)
   - Click **🔍 Search** để tìm kiếm
   - **Xem statistics**:
     - Total Activities
     - Completed
     - Active
     - Average Duration
   - Xem timeline của các activities được thực thi
   - Click **Details** để xem thông tin chi tiết activity

5. **Tab Process History** 📊:
   - Tìm kiếm theo Process Instance ID hoặc Process Definition Key
   - Filter theo status (Finished/Running/All)
   - Click **View Details** để xem toàn bộ thông tin process

---

## So sánh với Enterprise Edition

| Tính năng | Community Edition | Enterprise Edition |
|-----------|-------------------|-------------------|
| Runtime view | ✅ | ✅ |
| History tab trong Cockpit | ❌ | ✅ |
| REST API history | ✅ | ✅ |
| Database query | ✅ | ✅ |
| Operation Log UI | ✅ (Custom) | ✅ (Built-in) |
| Audit Trail UI | ✅ (Custom) | ✅ (Built-in) |
| Custom UI (như đã tạo) | ✅ | ✅ |
| Process modification | ❌ | ✅ |
| Process migration | ❌ | ✅ |
| Advanced analytics | ❌ | ✅ |

---

## Kết luận

Trong **Community Edition**, bạn **KHÔNG thể chuyển sang chế độ khác trong Cockpit UI** vì tính năng này chỉ có trong Enterprise Edition.

Tuy nhiên, bạn có thể:
- ✅ Sử dụng REST API
- ✅ Sử dụng custom web UI (đã tạo sẵn)
- ✅ Query database trực tiếp
- ✅ Tạo custom endpoints (đã tạo sẵn)

Tất cả đều cho phép bạn xem đầy đủ history, audit logs và audit trail của process instances.

