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
      </view>

      <!-- 四个功能按钮区域 -->
      <view class="button-group">
        <span class="icon-button" @click="makePhoneCall">
          <image src="/static/icons/dianhua.png" mode="aspectFit" class="icon-image"></image>
        </span>
        <span class="icon-button" @click="openWeChat">
          <image src="/static/icons/weixin2.png" mode="aspectFit" class="icon-image"></image>
        </span>
        <span class="icon-button" @click="openMap">
          <image src="/static/icons/ditu.png" mode="aspectFit" class="icon-image"></image>
        </span>
        <span class="icon-button" @click="openCalendar">
          <image src="/static/icons/rili.png" mode="aspectFit" class="icon-image"></image>
        </span>
      </view>
  
      <!-- 在线预约按钮，靠近底部 -->
      <button class="reserve-button" @click="makeReservation">在线预约</button>

      <!-- 团队成员标题 -->
    <view class="team-section">
      <text class="team-title">团队成员</text>
      <text class="team-subtitle">Team</text>
    </view>

    <!-- 团队成员列表 -->
    <view class="member-list">
      <view v-for="(member, index) in teamMembers" :key="index" 
            :class="['member', { 'member-reverse': index % 2 === 1 }]">
        <image :src="member.avatar" class="member-avatar" mode="widthFix"></image>
        <view class="member-info">
          <text class="member-name">{{ member.name }}</text>
          <text class="member-title">{{ member.title }}</text>
          <text class="member-description">{{ member.description }}</text>
        </view>
      </view>
    </view>

    <!-- 自定义弹出层 -->
    <ModalPopup
      v-if="showWeChat"
      :isVisible="showWeChat"
      title="枝南影像"
      imageSrc="/static/info/wechat.png"
      description="长按添加微信"
      @close="showWeChat = false"
    />

    <!-- 底部信息区域 -->
    <FooterInfo />
  </view>
</template>

<script>
  import FooterInfo from '@/components/FooterInfo/FooterInfo.vue'
  import ModalPopup from "@/components/ModalPopup/ModalPopup.vue";

  export default {
    components: {
      FooterInfo,
      ModalPopup
    },
    data() {
      return {
        showWeChat: false, // 控制弹出层的显示

        teamMembers: [
          {
            name: '风言雾语',
            title: '摄影师',
            description: '枝南影像创始人，从业11年，千余场世界各地婚礼摄影经验，中国婚礼摄影奖项获得者',
            avatar: '/static/about/member1.png'
          },
          {
            name: '一方',
            title: '妆造师',
            description: '擅长捕捉婚礼中的动人瞬间，以其独特的视角和深厚的摄影功底受到客户一致好评',
            avatar: '/static/about/member2.png'
          },
          // 更多成员信息...
        ]
      };
    },
    methods: {
      makePhoneCall() {
        uni.makePhoneCall({
          phoneNumber: '18365519973'
        });
      },
      openWeChat() {
        this.showWeChat = true; // 显示自定义弹出层
      },
      closeWeChat() {
        this.showWeChat = false; // 隐藏自定义弹出层
      },
      openMap() {
        uni.openLocation({
          latitude: 22.732733,         // 替换为你的目标纬度
          longitude: 114.137469,       // 替换为你的目标经度
          name: "枝南影像",         // 地点名称
          address: "广东省东莞市凤岗镇保利百合花园", // 详细地址
          scale: 18,                // 地图缩放级别，范围5-18，默认18
          success: () => {
            console.log("地图打开成功");
          },
          fail: (err) => {
            console.error("地图打开失败", err);
          }
        });
      },
      openCalendar() {
        uni.showToast({
          title: '开发中，敬请期待( •̀ .̫ •́ )✧', // 显示的提示内容
          icon: 'none',        // 不使用图标
          duration: 1000       // 显示时长，单位为毫秒（2秒后消失）
        });
      },
      makeReservation() {
        uni.showToast({
          title: '开发中，敬请期待( •̀ .̫ •́ )✧', // 显示的提示内容
          icon: 'none',        // 不使用图标
          duration: 1000       // 显示时长，单位为毫秒（2秒后消失）
        });
      }
    }
  };
</script>

  
<style scoped>
  @import url("about.css")
</style>
  