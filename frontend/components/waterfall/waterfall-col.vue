<template>
	<view class="waterfall-col" :style="{width:colWidth}">
		<slot name="default"></slot>
	</view>
</template>

<script>
	
		
	export default {
		name:"helangWaterfallCol",
		options:{
			virtualHost: true
		},
		components: {
			
		},
		props:{
			// 栏目宽度
			colWidth:{
				type: String,
				default:''
			},
		},
		watch:{
			
		},
		computed:{
			
		},
		data() {
			return {
				
			}
		},
		mounted() {
			
		},
		methods: {
			
		}
	}
</script>

<style lang="scss" scoped>
	.waterfall-col{
		padding:0 10rpx;
		box-sizing: border-box;
	}
</style>