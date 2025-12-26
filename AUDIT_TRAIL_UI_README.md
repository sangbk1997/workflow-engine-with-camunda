# 🔍 Operation Log & Audit Trail Viewer

## Tổng quan

Một giao diện web hiện đại và đầy đủ tính năng để theo dõi, giám sát và audit tất cả các operations, activities và process instances trong Camunda 7 Community Edition.

## ✨ Tính năng chính

### 📋 Operation Log Tab
Theo dõi tất cả các thao tác được thực hiện trên hệ thống:

- ✅ **Comprehensive Operation Tracking**
  - Xem tất cả operations: Create, Update, Delete, Complete, Claim, Activate, Suspend
  - Theo dõi ai đã làm gì, khi nào, và ở đâu
  - Hiển thị thay đổi giá trị (Old Value → New Value)

- 🔍 **Advanced Filtering**
  - Filter theo Process Instance ID
  - Filter theo User ID
  - Filter theo Operation Type
  - Filter theo Entity Type (ProcessInstance, Task, Variable, Job)
  - Filter theo khoảng thời gian (From/To Date)

- 📊 **Real-time Statistics**
  - Total Operations
  - Today's Operations
  - Unique Users
  - Failed Operations

- 💾 **Export Capabilities**
  - Export to CSV
  - Download audit reports

### 🔐 Audit Trail Tab
Xem lịch sử chi tiết của các activities trong process:

- ✅ **Activity Instance History**
  - Xem timeline đầy đủ của process execution
  - Theo dõi từng bước của workflow
  - Hiển thị start time, end time, duration

- 🔍 **Smart Filtering**
  - Filter theo Process Instance ID
  - Filter theo Process Definition Key
  - Filter theo Activity Type (userTask, serviceTask, gateway, etc.)

- 📊 **Analytics**
  - Total Activities
  - Completed Activities
  - Active Activities
  - Average Duration

- ⏱️ **Real-time Status**
  - Live tracking của running activities
  - Visual status indicators (Completed/Active/Running)

### 📊 Process History Tab
Quản lý và tra cứu process instances:

- ✅ **Flexible Search**
  - Search by Process Instance ID
  - Search by Process Definition Key
  - Filter by Status (Finished/Running/All)

- 📈 **Process Information**
  - Complete process details
  - Start/End times
  - Duration tracking
  - State monitoring

- 🔗 **Quick Navigation**
  - Jump to full process details
  - Link to operation logs
  - Link to audit trail

## 🎨 UI Features

### Modern Design
- Clean and intuitive interface
- Responsive layout
- Color-coded status badges
- Interactive tables

### User Experience
- Tabbed navigation
- Real-time data loading
- Loading indicators
- Error handling
- Empty state messages

### Data Visualization
- Statistics cards with gradient backgrounds
- Color-coded badges for different statuses
- Timestamp formatting
- Duration formatting
- ID shortening for better readability

## 🚀 Cách sử dụng

### 1. Khởi động ứng dụng
```bash
cd /path/to/acm-vortex-workflow-engine
mvn spring-boot:run
```

### 2. Truy cập UI
Mở trình duyệt và truy cập:
```
http://localhost:8080/audit-trail.html
```

### 3. Sử dụng Operation Log

#### Bước 1: Chọn tab "Operation Log"
- Tab này mở mặc định khi load trang

#### Bước 2: Áp dụng filters (optional)
- **Process Instance ID**: Nhập ID cụ thể để xem operations của process đó
- **User ID**: Nhập username để xem operations của user cụ thể
- **Operation Type**: Chọn loại operation (Create, Update, Delete, etc.)
- **Entity Type**: Chọn loại entity (ProcessInstance, Task, Variable, Job)
- **Date Range**: Chọn khoảng thời gian

#### Bước 3: Tìm kiếm
- Click nút **🔍 Search** để áp dụng filters
- Click **🔄 Clear Filters** để xóa tất cả filters

#### Bước 4: Xem kết quả
- Xem statistics ở phía trên
- Scroll qua bảng để xem chi tiết operations
- Click **Details** để xem thông tin đầy đủ

#### Bước 5: Export (optional)
- Click **📥 Export CSV** để tải về file CSV

### 4. Sử dụng Audit Trail

#### Bước 1: Chọn tab "Audit Trail"
- Click vào tab thứ hai

#### Bước 2: Áp dụng filters
- **Process Instance ID**: Xem activities của process cụ thể
- **Process Definition Key**: Lọc theo loại process (e.g., loanApprovalApp)
- **Activity Type**: Chọn loại activity (userTask, serviceTask, etc.)

#### Bước 3: Xem timeline
- Xem danh sách activities theo thời gian
- Kiểm tra trạng thái (Completed/Active)
- Xem duration của từng activity

#### Bước 4: Analyze statistics
- Xem tổng số activities
- Theo dõi số lượng completed vs active
- Kiểm tra average duration

### 5. Sử dụng Process History

#### Bước 1: Chọn tab "Process History"
- Click vào tab thứ ba

#### Bước 2: Tìm kiếm
- Nhập Process Instance ID hoặc Process Definition Key
- Chọn status filter

#### Bước 3: Xem results
- Click **View Details** để xem thông tin đầy đủ của process

## 📊 API Endpoints được sử dụng

### Camunda REST API
```bash
# Operation Logs
GET /engine-rest/history/user-operation

# Activity Instances
GET /engine-rest/history/activity-instance

# Process Instances
GET /engine-rest/history/process-instance
```

### Custom API (nếu có HistoryController)
```bash
# Process Details
GET /api/history/process-instance/{id}

# Finished Processes
GET /api/history/finished-processes

# Running Processes
GET /api/history/running-processes
```

## 🎯 Use Cases

### 1. Compliance & Audit
- Theo dõi ai đã thực hiện operations nào
- Export audit reports cho compliance
- Xem lịch sử thay đổi

### 2. Troubleshooting
- Debug process failures
- Xem activity timeline để tìm bottlenecks
- Kiểm tra operation logs để tìm errors

### 3. Performance Monitoring
- Theo dõi average duration
- Identify slow activities
- Monitor operation frequency

### 4. User Activity Tracking
- Xem operations của từng user
- Track user productivity
- Audit user actions

### 5. Process Analysis
- Analyze process flow
- Compare process instances
- Identify patterns

## 🔧 Customization

### Thay đổi API Base URL
Mở file `audit-trail.html` và sửa:
```javascript
const API_BASE = 'http://localhost:8080'; // Thay đổi URL
```

### Thêm filters mới
Thêm form inputs trong các search sections và cập nhật build params functions.

### Customize statistics
Sửa các functions `updateOperationLogStats` và `updateAuditStats`.

## 🐛 Troubleshooting

### Không load được data?
1. Kiểm tra Camunda đã chạy chưa: `http://localhost:8080`
2. Kiểm tra CORS settings
3. Mở DevTools Console để xem errors

### Filter không hoạt động?
1. Xóa cache browser
2. Reload trang
3. Kiểm tra API parameters

### Export không hoạt động?
- Tính năng export CSV đang ở dạng placeholder
- Cần implement thêm logic export

## 📝 Notes

- UI này sử dụng Camunda REST API có sẵn
- Không cần Enterprise Edition
- Hoạt động với Camunda 7.x
- Tương thích với mọi browser hiện đại

## 🔐 Security Considerations

- Không có authentication built-in
- Nên deploy sau reverse proxy với authentication
- Hạn chế access trong production
- Consider CORS policies

## 📚 Tài liệu tham khảo

- [Camunda REST API Documentation](https://docs.camunda.org/manual/latest/reference/rest/)
- [History Service](https://docs.camunda.org/manual/latest/user-guide/process-engine/history/)
- [Operation Log](https://docs.camunda.org/manual/latest/user-guide/process-engine/history/#user-operation-log)

## 🎉 Tính năng sắp tới

- [ ] Advanced export (PDF, Excel)
- [ ] Chart visualization
- [ ] Real-time updates (WebSocket)
- [ ] Custom date range presets
- [ ] Saved filters
- [ ] Dashboard with widgets
- [ ] Notification system
- [ ] Multi-language support

## 💡 Tips

1. **Sử dụng Date Filters**: Giới hạn kết quả để tăng performance
2. **Export thường xuyên**: Lưu audit logs định kỳ
3. **Monitor Statistics**: Theo dõi trends qua thời gian
4. **Combine Filters**: Kết hợp nhiều filters để tìm chính xác
5. **Check Details**: Click Details để xem thông tin đầy đủ

---

**Tác giả**: AI Assistant  
**Version**: 1.0.0  
**Last Updated**: December 17, 2025

