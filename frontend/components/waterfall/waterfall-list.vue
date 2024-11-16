<template>
	<view>
		<view class="waterfall-box" :class="{'hidden':hideList}">
			<view class="h-flex-x helang-waterfall-slot">
				<slot name="default" v-bind:colWidth="colWidth" v-bind:list="renderList"></slot>
			</view>
		</view>
		<slot name="tips"></slot>
		<image 
			class="load-image" 
			v-for="(item,index) in waitRenderList" 
			:key="index" 
			v-if="item.__waterfall_imageHeight === undefined"
			:src="item | imageLoadUrl" 
			:data-index="index"
			@load="handleImageLoad" 
			@error="handleImageLoad"
		></image>
	</view>
</template>

<script>
	// 字符串前缀
	const prefixStr = '__waterfall_';
	// 图片高度键名
	const IMAGE_HEIGHT_KEY = `${prefixStr}imageHeight`;
	// 图片缓存键名
	const IMAGE_CACHE_KEY = `${prefixStr}cacheImageHeight`;
	// 是否渲染
	const ITEM_SHOW_KEY = `${prefixStr}show`;
	// 渲染ID
	const RENDER_ID = `${prefixStr}renderId`;
	// 图片链接键名
	let IMAGE_URL_KEY = '';
	// 图片PX宽度
	let IMAGE_PX_WIDTH = 0;
	// 渲染状态
	const RENDER_STATUS = {
		// 加载中
		LOADING: 'RENDER_LOADING',
		// 渲染开始
		START:'RENDER_START',
		// 渲染结束
		END:'RENDER_END'
	};
	
	export default {
		name:"helangWaterfallList",
		options:{
			virtualHost: true
		},
		components: {
			
		},
		props:{
			// 分栏
			col:{
				type: Number,
				default:2
			},
			// 图片宽度
			imageWidth:{
				type: Number | String,
				default:305
			},
			// 缓存高度(加速渲染)
			cacheImageHeight:{
				type: Boolean,
				default:true
			},
			// 图片链接键名
			imageKey:{
				type: String,
				default:'url'
			},
			// 隐藏列表
			hideList:{
				type: Boolean,
				default:false
			},
		},
		filters:{
			imageLoadUrl(data){
				return data[IMAGE_URL_KEY] ?? "";
			}
		},
		watch:{
			
		},
		computed:{
			colWidth(){
				return `${1 / this.$props.col * 100}%`;
			}
		},
		data() {
			return {
				// 待渲染列表
				waitRenderList:[],
				// 渲染列表
				renderList:[],
				// 渲染列表高度
				renderListHeight:[]
			}
		},
		created() {
			this.initRenderList();
			// 图片高度缓存
			if(!uni[IMAGE_CACHE_KEY]){
				uni[IMAGE_CACHE_KEY] = {};
			}
			IMAGE_URL_KEY = this.$props.imageKey;
			IMAGE_PX_WIDTH = this.getImagePxWidth();
		},
		mounted() {
			
		},
		methods: {
			// 初始化渲染列表
			initRenderList(){
				let list = [];
				let heights = [];
				for(let i = 0; i < this.$props.col; i++){
					list.push([]);
					heights.push(0);
				}
				this.renderList = list;
				this.renderListHeight = heights;
				this.waitPushIndex = 0;
			},
			// 获取图片的 px 宽度
			getImagePxWidth(){
				const width = this.$props.imageWidth;
				// 是否为px单位
				if(/^\d+px$/i.test(String(width))){
					return parseInt(width);
				}else{
					return uni.upx2px(parseInt(width) || 0)
				}
				
			},
			// 处理图片加载
			handleImageLoad(e){
				const { width = 0, height = 0 } = e.detail;
				const { index } = e.currentTarget.dataset;
				const renderHeight = width === 0 ? 0 : parseInt(IMAGE_PX_WIDTH / width * height);
				
				this.addWaitRenderListImageHeight(index, renderHeight);
				
				if(this.$props.cacheImageHeight){
					const imageUrl = this.waitRenderList[index][IMAGE_URL_KEY];
					if(imageUrl.length < 300){
						uni[IMAGE_CACHE_KEY][imageUrl] = renderHeight;
					}
				}
				
				if(this.checkImageHaveHeight()){
					this.emitRenderStatusChange(RENDER_STATUS.START);
					this.addRenderData();
				}
			},
			// 检查图片是否已全部包含高度
			checkImageHaveHeight(){
				const index = this.waitRenderList.findIndex((item)=>{
					return item[IMAGE_HEIGHT_KEY] === undefined;
				})
				return index === -1 ? true : false;
			},
			// 待渲染列表添加图片高度数据
			addWaitRenderListImageHeight(index,height){
				this.waitRenderList[index][IMAGE_HEIGHT_KEY] = height;
			},
			// 查找最小高度的列的索引
			findSmallColIndex(){
				let col = 0;
				let size = this.renderListHeight[col];
				this.renderListHeight.forEach((item,index)=>{
					if(item < size){
						col = index;
						size = item;
					}
				});
				return col;
			},
			// 追加渲染数据
			pushRender(data){
				const index = this.findSmallColIndex();
				
				this.renderList[index].push({
					...data,
					[IMAGE_HEIGHT_KEY]: `${data[IMAGE_HEIGHT_KEY]}px`,
					[RENDER_ID]: `${prefixStr}${Date.now()}`
				});
			},
			// 添加所有渲染数据，方案存在问题暂
			addAllRenderData(){
				this.waitPushIndex = 0;
				const add = ()=>{
					const current = this.waitRenderList[this.waitPushIndex];
					if(current === undefined){
						this.waitRenderList = [];
						return;
					}
					this.pushRender(current);
					this.waitPushIndex++;
					
					add();
				};
				add();
			},
			// 渲染状态变更
			emitRenderStatusChange(status){
				this.$emit("statusChange",status);
			},
			// 提交高度
			reportHeight(colIndex,height){
				this.renderListHeight[colIndex]+=height;
				this.addRenderData();
			},
			// 添加一条渲染数据
			addRenderData(){
				const index = this.waitPushIndex ?? 0;
				const current = this.waitRenderList[index];
				if(current === undefined){
					this.emitRenderStatusChange(RENDER_STATUS.END);
					return;
				}
				this.pushRender(current);
				this.waitPushIndex = index + 1;
				if(this.waitPushIndex >= this.waitRenderList.length){
					this.waitPushIndex = 0;
					this.waitRenderList = [];
				}
			},
			// 启动渲染
			render(list = [],reset = false){
				if(!list?.length > 0){
					console.log('河浪瀑布流插件提示：当前数据为空，不会触发列表渲染');
					return;
				}
				
				if(reset){
					this.initRenderList();
				}
				
				this.waitRenderList = list.map((item)=>{
					const imageUrl = item[IMAGE_URL_KEY];
					const imageHeight = uni[IMAGE_CACHE_KEY][imageUrl];
					const show = this.$props.sameHeight ? true:false;
					
					return {
						...item,
						[IMAGE_HEIGHT_KEY]: imageHeight,
						// 该属性暂未使用，预留字段
						[ITEM_SHOW_KEY]: show
					}
				});
				
				this.emitRenderStatusChange(RENDER_STATUS.LOADING);
				
				if(this.checkImageHaveHeight()){
					this.emitRenderStatusChange(RENDER_STATUS.START);
					this.addRenderData();
				}
			}
		}
	}
</script>

<style>
	/* 小程序模式下插槽会增加一个 <scoped-slots-default> DOM节点 */
	.helang-waterfall-slot > scoped-slots-default{
		width: 100%;
		display: flex;
		flex-direction: row;
		flex-wrap: wrap;
		justify-content: flex-start;
		align-items: flex-start;
		align-content: flex-start;
	}
</style>
<style lang="scss" scoped>
	.waterfall-box {
		padding: 0 10rpx;
		box-sizing: border-box;
		
		&.hidden{
			display: none;
		}
		
		.list-item{
			margin-bottom: 0;
			// 设置透明，默认是可视的
			opacity: 0;
			// 默认超出隐藏，不影响加载中的文字显示效果
			overflow: hidden;
			height: 0;
			
			&.show{
				margin-bottom: 20rpx;
				opacity: 1;
				overflow: auto;
				height: auto;
			}
		}
	}

	.h-flex-x {
		display: flex;
		flex-direction: row;
		flex-wrap: wrap;
		justify-content: flex-start;
		align-items: flex-start;
		align-content: flex-start;
	}
	
	.load-image{
		display: none !important;
	}
</style>