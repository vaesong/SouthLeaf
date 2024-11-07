<template>
    <view class="about-page">
      <!-- Logo和三行文本信息 -->
      <view class="logo-section">
        <image src="/static/about/logo.png" class="logo" mode="aspectFit"></image>
        <text class="title">枝南影像</text>
        <text class="tagline">记录时光中的最美瞬间</text>
        <text class="location">广东 深圳</text>
        
      </view>
  
      <!-- 公司简介区域，增加高度 -->
      <view class="description-section">
        <text class="quote left-quote">“</text> <!-- 左引号 -->
        <view class="description-text">
          枝南影像，是一个专注于高品质摄影服务的个人工作室。我们擅长捕捉生活中的每一个动人瞬间，无论是婚礼庆典的甜蜜、家庭聚会的温馨，还是个人肖像的独特魅力，都能通过我们的镜头得以完美呈现。
          我们力求每一次拍摄都能深刻反映客户的情感与故事，让每一张照片都成为独一无二的艺术品，珍藏永恒的美好回忆。
        </view>
        <text class="quote right-quote">”</text> <!-- 右引号 -->
        <text class="link" @click="openWebsite">http://www.cangvision.com</text>
      </view>

      <!-- 四个功能按钮区域 -->
      <view class="button-group">
        <button class="icon-button" @click="makePhoneCall">电话</button>
        <button class="icon-button" @click="openWeChat">微信</button>
        <button class="icon-button" @click="openLocation">地址</button>
        <button class="icon-button" @click="openCalendar">日历</button>
      </view>
  
      <!-- 在线预约按钮，靠近底部 -->
      <button class="reserve-button" @click="makeReservation">在线预约</button>
    </view>
</template>

<script>
  export default {
    methods: {
      openWebsite() {
        uni.navigateTo({
          url: 'http://www.cangvision.com'
        });
      },
      makePhoneCall() {
        uni.makePhoneCall({
          phoneNumber: '18365519973'
        });
      },
      openWeChat() {
        uni.showModal({
          title: '添加微信',
          content: '请添加微信号：your_wechat_id',
          showCancel: true, // 显示取消按钮
          confirmText: '复制微信号',
          success: (res) => {
            if (res.confirm) {
              // 用户点击了确认，可以执行复制操作
              uni.setClipboardData({
                data: 'your_wechat_id',
                success: () => {
                  uni.showToast({
                    title: '微信号已复制',
                    icon: 'success'
                  });
                }
              });
            }
          }
        });
      },
      openCalendar() {
        uni.showDatePicker({
          success: (res) => {
            uni.showToast({
              title: `选择的日期是：${res.date}`,
              icon: 'none'
            });
          },
          fail: () => {
            uni.showToast({
              title: '未选择日期',
              icon: 'none'
            });
          }
        });
      },
      openCalendar() {
        console.log('日历按钮被点击');
      },
      makeReservation() {
        uni.navigateTo({
          url: '/pages/reservation/reservation'
        });
      }
    }
  };
</script>
  
<style scoped>
  .about-page {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: space-between;
    height: 100vh;
    padding: 20px;
    box-sizing: border-box;
  }
  
  /* Logo和三行文字区域 */
  .logo-section {
    text-align: center;
    margin-top: 20px;
    display: flex;
    flex-direction: column;
    align-items: center; /* 确保内容水平居中 */
  }

 .logo {
  width: 80px;         /* 宽度 */
  height: 80px;        /* 高度 */
  border-radius: 50%;  /* 圆形效果 */
  margin-bottom: 10px;
  display: block;      /* 确保图片居中时不受其他元素影响 */
}


  .title, .tagline, .location {
    font-size: 16px;
    color: #333;
    margin-bottom: 5px;
  }
  
  /* 公司简介区域，增加高度和间距 */
  .description-section {
    position: relative; /* 使引号定位相对于该容器 */
    background-color: #f0f0f0;
    padding: 30px;
    margin-top: 20px;
    width: 100%;
    text-align: center;
    border-radius: 5px;
    font-size: 14px;
    line-height: 1.5;
    min-height: 120px;
  }

  .quote {
    position: absolute; /* 使引号绝对定位 */
    font-size: 50px; /* 调整引号大小 */
    color: #aaa; /* 引号颜色 */
  }

  .left-quote {
    top: -10px; /* 调整左引号位置 */
    left: -10px; /* 调整左引号位置 */
  }

  .right-quote {
    bottom: -30px; /* 调整右引号位置 */
    right: -10px; /* 调整右引号位置 */
  }

  .description-text {
    font-size: 14px;
    line-height: 1.5;
    color: #333;
    white-space: normal;
    word-break: break-word;
    text-align: left;
    margin-bottom: 10px; /* 段落之间的间距 */
  }

  /* 链接样式 */
  .link {
    color: #1e90ff;
    display: block;
    margin-top: 10px;
  }
  
  /* 功能按钮区域 */
  .button-group {
    display: flex;
    justify-content: space-around;
    width: 100%;
    margin-top: 20px;
    margin-bottom: 20px;
  }
  .icon-button {
    background-color: #fff;
    border: 1px solid #ddd;
    border-radius: 5px;
    padding: 10px 20px;
    font-size: 14px;
  }
  
  /* 在线预约按钮 */
  .reserve-button {
    background-color: #66cc99;
    color: white;
    font-size: 16px;
    padding: 10px 20px;
    border-radius: 5px;
    width: 80%;
    text-align: center;
    margin-bottom: 30px;
  }
</style>
  