<template>
	<view class="pkgdetail-page">
		<!-- 顶部图片 -->
		<image :src="pkg.imageSrc[0]" class="top-image" mode="aspectFill"></image>

		<!-- 描述内容（居中显示） -->
		<view class="description">
			<text class="description-text">{{ pkg.description }}</text>
		</view>
		
		<!-- 名称和价格部分 -->
		<view class="title-price-section">
			<text class="package-title">{{ pkg.title }}</text>
			
			<!-- 判断是否有活动价格 -->
			<view v-if="pkg.activityPrice" class="price-section">
				<text class="original-price">¥{{ pkg.price }}</text>
				<text class="activity-price">¥{{ pkg.activityPrice }} </text>
				<text class="activity-time">({{ formattedActivityTime }})</text>
				
			</view>

			<!-- 如果没有活动价格，直接显示原价 -->
			<text v-else class="package-price">¥{{ pkg.price }}</text>

		</view>

		<!-- 灰色分隔线 -->
		<view class="divider-line1"></view>

		<!-- 图标区域 -->
		<view class="icon-grid">
			<view v-for="(item, index) in iconItems" :key="index" class="icon-item">
				<image :src="item.icon" class="icon-image"></image>
				<text class="icon-text">{{ item.text }}</text>
			</view>
		</view>

		<!-- 另一个灰色分隔线 -->
		<view class="divider-line2"></view>

		<view class="content-wrapper">
			<SectionHeader
				leftColor="#4CAF50"
				title="服务详情"
				:showMore="false"
			/>
			<!-- 遍历内容，每一条单独显示并设置间距 -->
      <view v-for="(info, index) in pkg.services" :key="index" class="info-item">
        <text class="info-text">{{ info }}</text>
      </view>
		</view>

		<!-- 另一个灰色分隔线 -->
		<view class="divider-line2"></view>

		<view class="content-wrapper">
			<SectionHeader
				leftColor="#4CAF50"
				title="温馨提示"
				:showMore="false"
			/>
			<view v-for="(info, index) in pkg.reminder" :key="index" class="info-item">
        <text class="info-text">{{ info }}</text>
      </view>
		</view>

		<!-- 另一个灰色分隔线 -->
		<view class="divider-line2"></view>

		<view class="content-wrapper">
			<SectionHeader
				leftColor="#4CAF50"
				title="须知"
				:showMore="false"
			/>
			<view v-for="(info, index) in notices" :key="index" class="info-item">
        <text class="info-text">{{ info }}</text>
      </view>
		</view>

		<view v-for="(image, index) in pkg.imageSrc" :key="index" class="gallery-item">
			<image :src="image" mode="widthFix" class="gallery-image" />
		</view>

		<!-- 底部信息区域 -->
		<FooterInfo />
	</view>
</template>

<script>
import FooterInfo from '@/components/FooterInfo/FooterInfo.vue'
import ModalPopup from "@/components/ModalPopup/ModalPopup.vue";
import SectionHeader from "@/components/SectionHeader/SectionHeader.vue";

export default {
	components: {
		FooterInfo,
		ModalPopup,
		SectionHeader
	},
	data() {
		return {
			pkg: {
        id: null,
        title: '',
        description: '',
        category: '',
        imageSrc: [],
        price: null,
        activityPrice: null,
        activateStart: '',
        activateEnd: '',
        services: [],
				reminder: []
      },
			iconItems: [
				{ icon: '/static/icons/Duration.png', text: '全天' },
				{ icon: '/static/icons/Shooting.png', text: '拍摄100张' },
				{ icon: '/static/icons/Makeup.png', text: '化妆' },
				{ icon: '/static/icons/Costume.png', text: '多套服装' },
				{ icon: '/static/icons/Retouching.png', text: '15张精修' },
				{ icon: '/static/icons/All.png', text: '底片全送' },
				{ icon: '/static/icons/AddPhotos.png', text: '加片费' },
				{ icon: '/static/icons/PhotoAlbum.png', text: '实体相册' },
				{ icon: '/static/icons/Service.png', text: '更多服务' }
			],
			notices: [
				'预付定金是双方履行协约的承诺',
				'如因客户原因，未按期履约，并未同服务方协商，定金将不可退还',
				'如因服务方原因取消订单，将退还所有费用，包括定金',
			]
		};
	},
	computed: {
    // 格式化活动时间显示
    formattedActivityTime() {
      return `${this.pkg.activateStart} - ${this.pkg.activateEnd}`;
    }
  },
	onLoad(options) {
    const id = options.id;
    this.loadPkgDetails(id);
  },
	methods: {
		// 后端获取，根据id
		loadPkgDetails(id) {
      const pkg = {
        id: 1,
        title: '圣诞主题',
        description: '在这温馨璀璨的圣诞季，每一帧写真都定格了冬日里最温暖的笑容与梦幻光影。银铃轻响中，我们捕捉到了节日的魔法，让爱与祝福在镜头下静静绽放，封存成永恒的记忆篇章。',
        category: '写真',
        imageSrc: [
            '/static/works/kid_0.jpg',
            '/static/works/kid_1.jpg',
            '/static/works/kid_2.jpg',
            '/static/works/kid_3.jpg',
            '/static/works/kid_4.jpg',
            '/static/works/kid_5.jpg'
          ],
        price: 520,
        activityPrice: 328,
        activateStart: '2024-11-01',
        activateEnd: '2042-12-31',
        services: [
            '※ 服务内容：8小时拍摄，全天不少于1500张原片，精修保底80张，当日预告不少于20张',
            '※ 三机礼盒内容：U盘一个（内含电子版精修及jpg格式的原片）高级相册一本，秋季主题定制相框一幅',
            '※ 附加项目：当日如需快修加收RMB1500元，三机位不少于60张增加机位RMB4000/总监机位，RMB2500/普通机位',
            '※ 其它服务区域，请拨打服务热线4008-767-212，或请联系客服进行具体咨询'
        ],
				reminder: [
          '※ 我们将收取全款费用的50%作为定金，尾款在拍摄结束后5个工作日付清',
          '※ 产品交付时间15个工作日，以收到尾款开始计算',
          '※ 客户如需自行选片必须提前告知',
          '※ 我们不接受除高质量问题外任何原因的相册重做要求',
        ]
      };
      if (pkg && pkg.id === parseInt(id)) {
        this.pkg = pkg;
      }
    },
	}
};
</script>

<style scoped>
@import url("pkgdetail.css")
</style>
