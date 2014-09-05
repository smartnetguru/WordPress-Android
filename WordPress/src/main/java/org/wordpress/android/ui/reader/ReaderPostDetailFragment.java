package org.wordpress.android.ui.reader;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.wordpress.android.R;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.datasets.ReaderCommentTable;
import org.wordpress.android.datasets.ReaderLikeTable;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.models.ReaderComment;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.ui.WPActionBarActivity;
import org.wordpress.android.ui.reader.ReaderActivityLauncher.OpenUrlType;
import org.wordpress.android.ui.reader.ReaderTypes.ReaderPostListType;
import org.wordpress.android.ui.reader.ReaderWebView.ReaderCustomViewListener;
import org.wordpress.android.ui.reader.ReaderWebView.ReaderWebViewPageFinishedListener;
import org.wordpress.android.ui.reader.ReaderWebView.ReaderWebViewUrlClickListener;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderBlogActions;
import org.wordpress.android.ui.reader.actions.ReaderCommentActions;
import org.wordpress.android.ui.reader.actions.ReaderPostActions;
import org.wordpress.android.ui.reader.adapters.ReaderCommentAdapter;
import org.wordpress.android.ui.reader.utils.ReaderUtils;
import org.wordpress.android.ui.reader.utils.ReaderVideoUtils;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.DateTimeUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.EditTextUtils;
import org.wordpress.android.util.HtmlUtils;
import org.wordpress.android.util.PhotonUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.UrlUtils;
import org.wordpress.android.widgets.WPListView;
import org.wordpress.android.widgets.WPNetworkImageView;

public class ReaderPostDetailFragment extends Fragment
        implements WPListView.OnScrollDirectionListener,
                   AbsListView.OnScrollListener,
                   ReaderCustomViewListener,
                   ReaderWebViewPageFinishedListener,
                   ReaderWebViewUrlClickListener {

    private static final String KEY_SHOW_COMMENT_BOX = "show_comment_box";
    private static final String KEY_REPLY_TO_COMMENT_ID = "reply_to_comment_id";
    private static final String ARG_DISABLE_BLOCK_BLOG = "disable_block_blog";

    private long mPostId;
    private long mBlogId;
    private ReaderPost mPost;

    private ReaderPostListType mPostListType;

    private ViewGroup mLayoutIcons;
    private ViewGroup mLayoutLikes;
    private WPListView mListView;
    private ViewGroup mCommentFooter;
    private ProgressBar mProgressFooter;
    private ReaderWebView mReaderWebView;
    private ReaderLikingUsersView mLikingUsersView;

    private boolean mIsAddCommentBoxShowing;
    private long mReplyToCommentId = 0;
    private boolean mHasAlreadyUpdatedPost;
    private boolean mHasAlreadyRequestedPost;
    private boolean mIsUpdatingComments;
    private boolean mIsBlockBlogDisabled;

    private ReaderInterfaces.OnPostPopupListener mOnPopupListener;

    private long mTopMostCommentId;
    private int mTopMostCommentTop;
    private int mPrevScrollState = SCROLL_STATE_IDLE;

    private Parcelable mListState;
    private ResourceVars mResourceVars;

    private ReaderUtils.FullScreenListener mFullScreenListener;

    public static ReaderPostDetailFragment newInstance(long blogId, long postId) {
        return newInstance(blogId, postId, true, null);
    }

    public static ReaderPostDetailFragment newInstance(long blogId,
                                                       long postId,
                                                       boolean disableBlockBlog,
                                                       ReaderPostListType postListType) {
        AppLog.d(T.READER, "reader post detail > newInstance");

        Bundle args = new Bundle();
        args.putLong(ReaderConstants.ARG_BLOG_ID, blogId);
        args.putLong(ReaderConstants.ARG_POST_ID, postId);
        args.putBoolean(ARG_DISABLE_BLOCK_BLOG, disableBlockBlog);
        if (postListType != null) {
            args.putSerializable(ReaderConstants.ARG_POST_LIST_TYPE, postListType);
        }

        ReaderPostDetailFragment fragment = new ReaderPostDetailFragment();
        fragment.setArguments(args);

        return fragment;
    }

    /*
     * class which holds all resource-based variables used by this fragment
     */
    private static class ResourceVars {
        final int displayWidth;
        final int actionBarHeight;
        final int likeAvatarSize;
        final int videoOverlaySize;

        final int marginLarge;
        final int marginSmall;
        final int marginExtraSmall;
        final int listMarginWidth;
        final int fullSizeImageWidth;

        final int colorGreyExtraLight;
        final int mediumAnimTime;

        final String linkColorStr;
        final String greyLightStr;
        final String greyExtraLightStr;

        private ResourceVars(Context context) {
            Resources resources = context.getResources();

            displayWidth = DisplayUtils.getDisplayPixelWidth(context);
            actionBarHeight = DisplayUtils.getActionBarHeight(context);
            likeAvatarSize = resources.getDimensionPixelSize(R.dimen.avatar_sz_small);
            videoOverlaySize = resources.getDimensionPixelSize(R.dimen.reader_video_overlay_size);

            marginLarge = resources.getDimensionPixelSize(R.dimen.margin_large);
            marginSmall = resources.getDimensionPixelSize(R.dimen.margin_small);
            marginExtraSmall = resources.getDimensionPixelSize(R.dimen.margin_extra_small);
            listMarginWidth = resources.getDimensionPixelOffset(R.dimen.reader_list_margin);

            colorGreyExtraLight = resources.getColor(R.color.grey_extra_light);
            mediumAnimTime = resources.getInteger(android.R.integer.config_mediumAnimTime);

            linkColorStr = HtmlUtils.colorResToHtmlColor(context, R.color.reader_hyperlink);
            greyLightStr = HtmlUtils.colorResToHtmlColor(context, R.color.grey_light);
            greyExtraLightStr = HtmlUtils.colorResToHtmlColor(context, R.color.grey_extra_light);

            int imageWidth = displayWidth - (listMarginWidth * 2);
            boolean hasStaticMenuDrawer =
                    (context instanceof WPActionBarActivity)
                            && (((WPActionBarActivity) context).isStaticMenuDrawer());
            if (hasStaticMenuDrawer) {
                int drawerWidth = resources.getDimensionPixelOffset(R.dimen.menu_drawer_width);
                imageWidth -= drawerWidth;
            }
            fullSizeImageWidth = imageWidth;
        }
    }

    /*
     * adapter containing comments for this post
     */
    private ReaderCommentAdapter mCommentAdapter;

    private ReaderCommentAdapter getCommentAdapter() {
        if (mCommentAdapter == null) {
            ReaderInterfaces.DataLoadedListener dataLoadedListener = new ReaderInterfaces.DataLoadedListener() {
                @Override
                public void onDataLoaded(boolean isEmpty) {
                    if (isAdded()) {
                        // show footer below comments when comments exist
                        mCommentFooter.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                        // restore listView state (scroll position) if it was saved during rotation
                        if (mListState != null) {
                            if (!isEmpty) {
                                getListView().onRestoreInstanceState(mListState);
                            }
                            mListState = null;
                        }
                        if (mTopMostCommentId != 0) {
                            restoreTopmostComment();
                        }
                    }
                }
            };

            // adapter calls this when user taps reply icon
            ReaderCommentAdapter.RequestReplyListener replyListener = new ReaderCommentAdapter.RequestReplyListener() {
                @Override
                public void onRequestReply(long commentId) {
                    if (!mIsAddCommentBoxShowing) {
                        showAddCommentBox(commentId);
                    } else {
                        hideAddCommentBox();
                    }
                }
            };

            // adapter uses this to request more comments from server when it reaches the end and
            // detects that more comments exist on the server than are stored locally
            ReaderActions.DataRequestedListener dataRequestedListener = new ReaderActions.DataRequestedListener() {
                @Override
                public void onRequestData() {
                    if (!mIsUpdatingComments) {
                        AppLog.i(T.READER, "reader post detail > requesting newer comments");
                        updateComments(true);
                    }
                }
            };
            mCommentAdapter = new ReaderCommentAdapter(getActivity(), mPost, replyListener, dataLoadedListener, dataRequestedListener);
        }
        return mCommentAdapter;
    }

    /*
     * called before new comments are shown so the current topmost comment is remembered
     */
    private void retainTopmostComment() {
        int position = getListView().getFirstVisiblePosition();
        int numHeaders = getListView().getHeaderViewsCount();
        if (position > numHeaders) {
            mTopMostCommentId = getCommentAdapter().getItemId(position - numHeaders);
            View v = getListView().getChildAt(0);
            mTopMostCommentTop = (v != null ? v.getTop() : 0);
        } else {
            mTopMostCommentId = 0;
            mTopMostCommentTop = 0;
        }
    }

    /*
     * called after new comments are shown so the previous topmost comment is scrolled to
     */
    private void restoreTopmostComment() {
        if (mTopMostCommentId != 0) {
            int position = getCommentAdapter().indexOfCommentId(mTopMostCommentId);
            if (position > -1) {
                getListView().setSelectionFromTop(position + getListView().getHeaderViewsCount(), mTopMostCommentTop);
            }
            mTopMostCommentId = 0;
            mTopMostCommentTop = 0;
        }
    }

    @Override
    public void setArguments(Bundle args) {
        super.setArguments(args);
        if (args != null) {
            mBlogId = args.getLong(ReaderConstants.ARG_BLOG_ID);
            mPostId = args.getLong(ReaderConstants.ARG_POST_ID);
            mIsBlockBlogDisabled = args.getBoolean(ARG_DISABLE_BLOCK_BLOG);
            if (args.containsKey(ReaderConstants.ARG_POST_LIST_TYPE)) {
                mPostListType = (ReaderPostListType) args.getSerializable(ReaderConstants.ARG_POST_LIST_TYPE);
            }
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        mResourceVars = new ResourceVars(activity);

        if (activity instanceof ReaderUtils.FullScreenListener) {
            mFullScreenListener = (ReaderUtils.FullScreenListener) activity;
        }

        if (activity instanceof ReaderInterfaces.OnPostPopupListener) {
            mOnPopupListener = (ReaderInterfaces.OnPostPopupListener) activity;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.reader_fragment_post_detail, container, false);
        final Context context = container.getContext();

        // locate & init listView
        mListView = (WPListView) view.findViewById(android.R.id.list);
        if (isFullScreenSupported()) {
            mListView.setOnScrollDirectionListener(this);
            mListView.setOnScrollListener(this);
            ReaderUtils.addListViewHeader(mListView, mResourceVars.actionBarHeight);
        }

        // add post detail as header to listView - must be done before setting adapter
        ViewGroup headerDetail = (ViewGroup) inflater.inflate(R.layout.reader_listitem_post_detail, mListView, false);
        mListView.addHeaderView(headerDetail, null, false);

        // add listView footer containing progress bar - footer appears whenever there are comments,
        // progress bar appears when loading new comments
        mCommentFooter = (ViewGroup) inflater.inflate(R.layout.reader_footer_progress, mListView, false);
        mCommentFooter.setVisibility(View.GONE);
        mCommentFooter.setBackgroundColor(mResourceVars.colorGreyExtraLight);
        mProgressFooter = (ProgressBar) mCommentFooter.findViewById(R.id.progress_footer);
        mProgressFooter.setVisibility(View.INVISIBLE);
        mListView.addFooterView(mCommentFooter);

        mLayoutIcons = (ViewGroup) view.findViewById(R.id.layout_actions);
        mLayoutLikes = (ViewGroup) view.findViewById(R.id.layout_likes);
        mLikingUsersView = (ReaderLikingUsersView) mLayoutLikes.findViewById(R.id.layout_liking_users_view);

        // setup the ReaderWebView
        mReaderWebView = (ReaderWebView) view.findViewById(R.id.reader_webview);
        mReaderWebView.setCustomViewListener(this);
        mReaderWebView.setUrlClickListener(this);
        mReaderWebView.setPageFinishedListener(this);

        // hide icons until the post is loaded
        mLayoutIcons.setVisibility(View.INVISIBLE);

        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mReaderWebView != null) {
            mReaderWebView.destroy();
        }
    }

    private WPListView getListView() {
        return mListView;
    }

    @Override
    public void onScrollUp() {
        // return from full screen when scrolling up unless user is typing a comment
        if (isFullScreen() && !mIsAddCommentBoxShowing) {
            setIsFullScreen(false);
        }
    }

    @Override
    public void onScrollDown() {
        // don't change fullscreen if user is typing a comment
        if (mIsAddCommentBoxShowing) {
            return;
        }

        boolean isFullScreen = isFullScreen();
        boolean canScrollDown = mListView.canScrollDown();
        boolean canScrollUp = mListView.canScrollUp();

        if (isFullScreen && !canScrollDown) {
            // disable full screen once user hits the bottom
            setIsFullScreen(false);
        } else if (!isFullScreen && canScrollDown && canScrollUp) {
            // enable full screen when scrolling down
            setIsFullScreen(true);
        }
    }

    /*
     * detect when listView fling completes so we can return from full screen if necessary
     */
    @Override
    public void onScrollStateChanged(AbsListView absListView, int scrollState) {
        if (scrollState == SCROLL_STATE_IDLE && mPrevScrollState == SCROLL_STATE_FLING) {
            if (isFullScreen() && !mListView.canScrollDown()) {
                setIsFullScreen(false);
            }
        }
        mPrevScrollState = scrollState;
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        // nop
    }


    private boolean hasPost() {
        return (mPost != null);
    }

    private boolean canBlockBlog() {
        return mPost != null
                && !mIsBlockBlogDisabled
                && !mPost.isPrivate
                && !mPost.isExternal
                && (mOnPopupListener != null)
                && (getPostListType() == ReaderPostListType.TAG_FOLLOWED);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.clear();
        inflater.inflate(R.menu.reader_native_detail, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_browse:
                if (hasPost()) {
                    ReaderActivityLauncher.openUrl(getActivity(), mPost.getUrl(), OpenUrlType.EXTERNAL);
                }
                return true;
            case R.id.menu_share:
                AnalyticsTracker.track(AnalyticsTracker.Stat.SHARED_ITEM);
                sharePage();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /*
     * full-screen mode hides the ActionBar and icon bar
     */
    private boolean isFullScreen() {
        return (mFullScreenListener != null && mFullScreenListener.isFullScreen());
    }

    private boolean isFullScreenSupported() {
        return (mFullScreenListener != null && mFullScreenListener.isFullScreenSupported());
    }

    private void setIsFullScreen(boolean enableFullScreen) {
        // this tells the host activity to enable/disable fullscreen
        if (mFullScreenListener != null && mFullScreenListener.onRequestFullScreen(enableFullScreen)) {
            if (mPost.isWP()) {
                animateIconBar(!enableFullScreen);
            }
        }
    }

    private ReaderPostListType getPostListType() {
        return (mPostListType != null ? mPostListType : ReaderTypes.DEFAULT_POST_LIST_TYPE);
    }

    private boolean isBlogPreview() {
        return (getPostListType() == ReaderPostListType.BLOG_PREVIEW);
    }

    /*
     * animate in/out the layout containing the reblog/comment/like icons
     */
    private void animateIconBar(boolean isAnimatingIn) {
        if (isAnimatingIn && mLayoutIcons.getVisibility() == View.VISIBLE) {
            return;
        }
        if (!isAnimatingIn && mLayoutIcons.getVisibility() != View.VISIBLE) {
            return;
        }

        final Animation animation;
        if (isAnimatingIn) {
            animation = new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF,
                    1.0f, Animation.RELATIVE_TO_SELF, 0.0f);
        } else {
            animation = new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF,
                    0.0f, Animation.RELATIVE_TO_SELF, 1.0f);
        }

        animation.setDuration(mResourceVars.mediumAnimTime);

        mLayoutIcons.clearAnimation();
        mLayoutIcons.startAnimation(animation);
        mLayoutIcons.setVisibility(isAnimatingIn ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putLong(ReaderConstants.ARG_BLOG_ID, mBlogId);
        outState.putLong(ReaderConstants.ARG_POST_ID, mPostId);

        outState.putBoolean(ReaderConstants.KEY_ALREADY_UPDATED, mHasAlreadyUpdatedPost);
        outState.putBoolean(ReaderConstants.KEY_ALREADY_REQUESTED, mHasAlreadyRequestedPost);
        outState.putBoolean(ARG_DISABLE_BLOCK_BLOG, mIsBlockBlogDisabled);
        outState.putBoolean(KEY_SHOW_COMMENT_BOX, mIsAddCommentBoxShowing);
        outState.putSerializable(ReaderConstants.ARG_POST_LIST_TYPE, getPostListType());

        if (mIsAddCommentBoxShowing) {
            outState.putLong(KEY_REPLY_TO_COMMENT_ID, mReplyToCommentId);
        }

        // retain listView state if a comment has been scrolled to - this enables us to restore
        // the scroll position after comment data is reloaded
        if (getListView() != null && getListView().getFirstVisiblePosition() > 0) {
            mListState = getListView().onSaveInstanceState();
            outState.putParcelable(ReaderConstants.KEY_LIST_STATE, mListState);
        } else {
            mListState = null;
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setHasOptionsMenu(true);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        }

        restoreState(savedInstanceState);
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mBlogId = savedInstanceState.getLong(ReaderConstants.ARG_BLOG_ID);
            mPostId = savedInstanceState.getLong(ReaderConstants.ARG_POST_ID);
            mHasAlreadyUpdatedPost = savedInstanceState.getBoolean(ReaderConstants.KEY_ALREADY_UPDATED);
            mHasAlreadyRequestedPost = savedInstanceState.getBoolean(ReaderConstants.KEY_ALREADY_REQUESTED);
            mIsBlockBlogDisabled = savedInstanceState.getBoolean(ARG_DISABLE_BLOCK_BLOG);
            if (savedInstanceState.getBoolean(KEY_SHOW_COMMENT_BOX)) {
                long replyToCommentId = savedInstanceState.getLong(KEY_REPLY_TO_COMMENT_ID);
                showAddCommentBox(replyToCommentId);
            }
            if (savedInstanceState.containsKey(ReaderConstants.ARG_POST_LIST_TYPE)) {
                mPostListType = (ReaderPostListType) savedInstanceState.getSerializable(ReaderConstants.ARG_POST_LIST_TYPE);
            }
            mListState = savedInstanceState.getParcelable(ReaderConstants.KEY_LIST_STATE);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!hasPost()) {
            showPost();
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // this ensures embedded videos don't continue to play when the fragment is no longer
        // active or has been detached
        pauseWebView();
    }

    /*
     * changes the like on the passed post
     */
    private void togglePostLike(ReaderPost post, View likeButton) {
        boolean isSelected = likeButton.isSelected();
        likeButton.setSelected(!isSelected);

        boolean isAskingToLike = !post.isLikedByCurrentUser;
        ReaderAnim.animateLikeButton(likeButton, isAskingToLike);

        if (!ReaderPostActions.performLikeAction(post, isAskingToLike)) {
            likeButton.setSelected(isSelected);
            return;
        }

        // get the post again since it has changed, then refresh to show changes
        mPost = ReaderPostTable.getPost(mBlogId, mPostId);
        refreshLikes();

        if (isAskingToLike) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.READER_LIKED_ARTICLE);
        }
    }

    /*
     * change the follow state of the blog the passed post is in
     */
    private void togglePostFollowed(ReaderPost post, View followButton) {
        boolean isSelected = followButton.isSelected();
        followButton.setSelected(!isSelected);
        ReaderAnim.animateFollowButton(followButton);

        final boolean isAskingToFollow = !post.isFollowedByCurrentUser;
        ReaderActions.ActionListener actionListener = new ReaderActions.ActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                if (!succeeded && isAdded()) {
                    int resId = (isAskingToFollow ? R.string.reader_toast_err_follow_blog : R.string.reader_toast_err_unfollow_blog);
                    ToastUtils.showToast(getActivity(), resId);
                }
            }
        };
        if (!ReaderBlogActions.performFollowAction(post, isAskingToFollow, actionListener)) {
            followButton.setSelected(isSelected);
            return;
        }

        // get the post again, since it has changed
        mPost = ReaderPostTable.getPost(mBlogId, mPostId);

        // call returns before api completes, but local version of post will have been changed
        // so refresh to show those changes
        refreshFollowed();
    }

    /*
     * called when user chooses to reblog the post
     */
    private void reblogPost() {
        if (!isAdded() || !hasPost()) {
            return;
        }

        if (mPost.isRebloggedByCurrentUser) {
            ToastUtils.showToast(getActivity(), R.string.reader_toast_err_already_reblogged);
            return;
        }

        final ImageView imgBtnReblog = (ImageView) mLayoutIcons.findViewById(R.id.image_reblog_btn);
        ReaderAnim.animateReblogButton(imgBtnReblog);
        ReaderActivityLauncher.showReaderReblogForResult(getActivity(), mPost, imgBtnReblog);
    }

    /*
     * called after the post has been reblogged
     */
    void doPostReblogged() {
        if (!isAdded()) {
            return;
        }

        // get the post again since reblog status has changed
        mPost = ReaderPostTable.getPost(mBlogId, mPostId);

        final ImageView imgBtnReblog = (ImageView) mLayoutIcons.findViewById(R.id.image_reblog_btn);
        imgBtnReblog.setSelected(mPost != null && mPost.isRebloggedByCurrentUser);
    }

    /*
     * display the standard Android share chooser to share a link to this post
     */
    private void sharePage() {
        if (!isAdded() || !hasPost())
            return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, mPost.getUrl());
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.reader_share_subject, getString(R.string.app_name)));
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.reader_share_link)));
        } catch (android.content.ActivityNotFoundException ex) {
            ToastUtils.showToast(getActivity(), R.string.reader_toast_err_share_intent);
        }
    }

    /*
     * get the latest version of this post so we can show the latest likes/comments
     */
    private void updatePost() {
        if (!hasPost() || !mPost.isWP()) {
            return;
        }

        final int numLikesBefore = ReaderLikeTable.getNumLikesForPost(mPost);

        ReaderActions.UpdateResultListener resultListener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                if (!isAdded()) {
                    return;
                }
                // reload the post if it has changed
                if (result == ReaderActions.UpdateResult.CHANGED) {
                    mPost = ReaderPostTable.getPost(mBlogId, mPostId);
                }
                if (result != ReaderActions.UpdateResult.FAILED) {
                    // refresh likes if necessary - done regardless of whether the post has changed
                    // since it's possible likes weren't stored until the post was updated
                    if (numLikesBefore != ReaderLikeTable.getNumLikesForPost(mPost)) {
                        refreshLikes();
                    }
                    // request comments if the post says the comment count is different than
                    // the number of stored comments
                    if (mPost.numReplies != ReaderCommentTable.getNumCommentsForPost(mPost)) {
                        updateComments(false);
                    }
                }
            }
        };
        ReaderPostActions.updatePost(mPost, resultListener);
    }

    /*
     * request comments for this post
     */
    private void updateComments(boolean requestNewer) {
        if (!hasPost() || !mPost.isWP()) {
            return;
        }
        if (mIsUpdatingComments) {
            AppLog.w(T.READER, "reader post detail > already updating comments");
            return;
        }

        AppLog.d(T.READER, "reader post detail > updateComments");
        mIsUpdatingComments = true;

        // show progress if we're requesting newer comments
        if (requestNewer) {
            showProgressFooter();
        }

        ReaderActions.UpdateResultListener resultListener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                mIsUpdatingComments = false;
                if (!isAdded()) {
                    return;
                }
                hideProgressFooter();
                if (result == ReaderActions.UpdateResult.CHANGED) {
                    retainTopmostComment();
                    refreshComments();
                }
            }
        };
        ReaderCommentActions.updateCommentsForPost(mPost, requestNewer, resultListener);
    }

    /*
     * show progress bar at the bottom of the screen - used when getting newer comments
     */
    private void showProgressFooter() {
        if (mProgressFooter != null && mProgressFooter.getVisibility() != View.VISIBLE) {
            mProgressFooter.setVisibility(View.VISIBLE);
        }
    }

    /*
     * hide the footer progress bar if it's showing
     */
    private void hideProgressFooter() {
        if (mProgressFooter != null && mProgressFooter.getVisibility() == View.VISIBLE) {
            mProgressFooter.setVisibility(View.INVISIBLE);
        }
    }

    /*
     * refresh adapter so latest comments appear
     */
    private void refreshComments() {
        AppLog.d(T.READER, "reader post detail > refreshComments");
        getCommentAdapter().refreshComments();
    }

    /*
     * show latest likes for this post
     */
    private void refreshLikes() {
        AppLog.d(T.READER, "reader post detail > refreshLikes");
        if (!isAdded() || !hasPost() || !mPost.isWP() || !mPost.isLikesEnabled) {
            return;
        }

        final TextView txtLikeCount = (TextView) mLayoutLikes.findViewById(R.id.text_like_count);
        txtLikeCount.setText(ReaderUtils.getLongLikeLabelText(getActivity(), mPost.numLikes, mPost.isLikedByCurrentUser));

        final ImageView imgBtnLike = (ImageView) getView().findViewById(R.id.image_like_btn);
        imgBtnLike.setSelected(mPost.isLikedByCurrentUser);
        imgBtnLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePostLike(mPost, imgBtnLike);
            }
        });

        // nothing more to do if no likes
        if (mPost.numLikes == 0) {
            if (mLayoutLikes.getVisibility() != View.GONE) {
                ReaderAnim.fadeOut(mLayoutLikes, ReaderAnim.Duration.SHORT);
            }
            return;
        }

        // clicking likes view shows activity displaying all liking users
        mLayoutLikes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ReaderActivityLauncher.showReaderLikingUsers(getActivity(), mPost);
            }
        });

        if (mLayoutLikes.getVisibility() != View.VISIBLE) {
            ReaderAnim.fadeIn(mLayoutLikes, ReaderAnim.Duration.SHORT);
        }

        mLikingUsersView.showLikingUsers(mPost);
    }

    /*
     * show the view enabling adding a comment - triggered when user hits comment icon/count in header
     * note that this view is hidden at design time, so it will be shown the first time user taps icon.
     * pass 0 for the replyToCommentId to add a parent-level comment to the post, or pass a real
     * comment id to reply to a specific comment
     */
    private void showAddCommentBox(final long replyToCommentId) {
        if (!isAdded())
            return;

        // skip if it's already showing or if a comment is currently being submitted
        if (mIsAddCommentBoxShowing || mIsSubmittingComment) {
            return;
        }

        final ViewGroup layoutCommentBox = (ViewGroup) getView().findViewById(R.id.layout_comment_box);
        final EditText editComment = (EditText) layoutCommentBox.findViewById(R.id.edit_comment);
        final ImageView imgBtnComment = (ImageView) getView().findViewById(R.id.image_comment_btn);

        // disable full-screen when comment box is showing
        if (isFullScreen()) {
            setIsFullScreen(false);
        }

        // different hint depending on whether user is replying to a comment or commenting on the post
        editComment.setHint(replyToCommentId == 0 ? R.string.reader_hint_comment_on_post : R.string.reader_hint_comment_on_comment);

        imgBtnComment.setSelected(true);
        AniUtils.flyIn(layoutCommentBox);

        editComment.requestFocus();
        editComment.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND) {
                    submitComment(replyToCommentId);
                }
                return false;
            }
        });

        // submit comment when image tapped
        final ImageView imgPostComment = (ImageView) getView().findViewById(R.id.image_post_comment);
        imgPostComment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitComment(replyToCommentId);
            }
        });

        EditTextUtils.showSoftInput(editComment);

        // if user is replying to another comment, highlight the comment being replied to and scroll
        // it to the top so the user can see which comment they're replying to - note that scrolling
        // is delayed to give time for listView to reposition due to soft keyboard appearing
        if (replyToCommentId != 0) {
            getCommentAdapter().setHighlightCommentId(replyToCommentId, false);
            getListView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    scrollToCommentId(replyToCommentId);
                }
            }, 300);
        }

        // mReplyToCommentId must be saved here so it can be stored by onSaveInstanceState()
        mReplyToCommentId = replyToCommentId;
        mIsAddCommentBoxShowing = true;
    }

    boolean isAddCommentBoxShowing() {
        return mIsAddCommentBoxShowing;
    }

    void hideAddCommentBox() {
        if (!isAdded() || !mIsAddCommentBoxShowing) {
            return;
        }

        final ViewGroup layoutCommentBox = (ViewGroup) getView().findViewById(R.id.layout_comment_box);
        final EditText editComment = (EditText) layoutCommentBox.findViewById(R.id.edit_comment);
        final ImageView imgBtnComment = (ImageView) getView().findViewById(R.id.image_comment_btn);

        imgBtnComment.setSelected(false);
        AniUtils.flyOut(layoutCommentBox);
        EditTextUtils.hideSoftInput(editComment);

        getCommentAdapter().setHighlightCommentId(0, false);

        mIsAddCommentBoxShowing = false;
        mReplyToCommentId = 0;
    }

    private void toggleShowAddCommentBox() {
        if (mIsAddCommentBoxShowing) {
            hideAddCommentBox();
        } else {
            showAddCommentBox(0);
        }
    }

    /*
     * scrolls the passed comment to the top of the listView
     */
    private void scrollToCommentId(long commentId) {
        int position = getCommentAdapter().indexOfCommentId(commentId);
        if (position > -1) {
            getListView().setSelectionFromTop(position + getListView().getHeaderViewsCount(), 0);
        }
    }

    /*
     * submit the text typed into the comment box as a comment on the current post
     */
    private boolean mIsSubmittingComment = false;

    private void submitComment(final long replyToCommentId) {
        final EditText editComment = (EditText) getView().findViewById(R.id.edit_comment);
        final String commentText = EditTextUtils.getText(editComment);
        if (TextUtils.isEmpty(commentText)) {
            return;
        }

        AnalyticsTracker.track(AnalyticsTracker.Stat.READER_COMMENTED_ON_ARTICLE);

        // hide the comment box - this provides immediate indication that comment is being posted
        // and prevents users from submitting the same comment twice
        hideAddCommentBox();

        // generate a "fake" comment id to assign to the new comment so we can add it to the db
        // and reflect it in the adapter before the API call returns
        final long fakeCommentId = ReaderCommentActions.generateFakeCommentId();

        mIsSubmittingComment = true;
        ReaderActions.CommentActionListener actionListener = new ReaderActions.CommentActionListener() {
            @Override
            public void onActionResult(boolean succeeded, ReaderComment newComment) {
                mIsSubmittingComment = false;
                if (!isAdded()) {
                    return;
                }
                if (succeeded) {
                    // comment posted successfully so stop highlighting the fake one and replace
                    // it with the real one
                    getCommentAdapter().setHighlightCommentId(0, false);
                    getCommentAdapter().replaceComment(fakeCommentId, newComment);
                    getListView().invalidateViews();
                } else {
                    // comment failed to post - show the comment box again with the comment text intact,
                    // and remove the "fake" comment from the adapter
                    editComment.setText(commentText);
                    showAddCommentBox(replyToCommentId);
                    getCommentAdapter().removeComment(fakeCommentId);
                    ToastUtils.showToast(getActivity(), R.string.reader_toast_err_comment_failed, ToastUtils.Duration.LONG);
                }
            }
        };

        final ReaderComment newComment = ReaderCommentActions.submitPostComment(mPost,
                fakeCommentId,
                commentText,
                replyToCommentId,
                actionListener);
        if (newComment != null) {
            editComment.setText(null);
            // add the "fake" comment to the adapter, highlight it, and show a progress bar
            // next to it while it's submitted
            getCommentAdapter().setHighlightCommentId(newComment.commentId, true);
            getCommentAdapter().addComment(newComment);
            // make sure it's scrolled into view
            scrollToCommentId(fakeCommentId);
        }
    }

    /*
     * refresh the follow button based on whether this is a followed blog
     */
    private void refreshFollowed() {
        if (!isAdded()) {
            return;
        }

        final TextView txtFollow = (TextView) getView().findViewById(R.id.text_follow);
        final boolean isFollowed = ReaderPostTable.isPostFollowed(mPost);

        ReaderUtils.showFollowStatus(txtFollow, isFollowed);
    }

    /*
     * creates formatted div for passed video with passed (optional) thumbnail
     */
    private static final String OVERLAY_IMG = "file:///android_asset/ic_reader_video_overlay.png";

    private String makeVideoDiv(String videoUrl, String thumbnailUrl) {
        if (TextUtils.isEmpty(videoUrl)) {
            return "";
        }

        // sometimes we get src values like "//player.vimeo.com/video/70534716" - prefix these with http:
        if (videoUrl.startsWith("//")) {
            videoUrl = "http:" + videoUrl;
        }

        int overlaySz = mResourceVars.videoOverlaySize / 2;
        if (TextUtils.isEmpty(thumbnailUrl)) {
            return String.format("<div class='wpreader-video' align='center'><a href='%s'><img style='width:%dpx; height:%dpx; display:block;' src='%s' /></a></div>", videoUrl, overlaySz, overlaySz, OVERLAY_IMG);
        } else {
            return "<div style='position:relative'>"
                    + String.format("<a href='%s'><img src='%s' style='width:100%%; height:auto;' /></a>", videoUrl, thumbnailUrl)
                    + String.format("<a href='%s'><img src='%s' style='width:%dpx; height:%dpx; position:absolute; left:0px; right:0px; top:0px; bottom:0px; margin:auto;'' /></a>", videoUrl, OVERLAY_IMG, overlaySz, overlaySz)
                    + "</div>";
        }
    }

    private boolean showPhotoViewer(String imageUrl, View source, int startX, int startY) {
        if (!isAdded() || TextUtils.isEmpty(imageUrl)) {
            return false;
        }

        // make sure this is a valid web image (could be file: or data:)
        if (!imageUrl.startsWith("http")) {
            return false;
        }

        boolean isPrivatePost = (mPost != null && mPost.isPrivate);

        ReaderActivityLauncher.showReaderPhotoViewer(
                getActivity(),
                imageUrl,
                getPostContent(),
                source,
                isPrivatePost,
                startX,
                startY);

        return true;
    }

    /*
     * returns the basic content of the post tweaked for use here
     */
    private String getPostContent() {
        if (mPost == null) {
            return "";
        } else if (mPost.hasText()) {
            // some content (such as Vimeo embeds) don't have "http:" before links, correct this here
            String content = mPost.getText().replace("src=\"//", "src=\"http://");
            // insert video div before content if this is a VideoPress post (video otherwise won't appear)
            if (mPost.isVideoPress) {
                content = makeVideoDiv(mPost.getFeaturedVideo(), mPost.getFeaturedImage()) + content;
            } else if (mPost.hasFeaturedImage() && !PhotonUtils.isMshotsUrl(mPost.getFeaturedImage())) {
                // if the post has a featured image other than an mshot that's not in the content,
                // add it to the content
                Uri uri = Uri.parse(mPost.getFeaturedImage());
                String path = StringUtils.notNullStr(uri.getLastPathSegment());
                if (!content.contains(path)) {
                    AppLog.d(T.READER, "reader post detail > added featured image to content");
                    content = String.format("<p><img class='img.size-full' src='%s' /></p>", mPost.getFeaturedImage())
                            + content;
                }
            }
            return content;
        } else if (mPost.hasFeaturedImage()) {
            // some photo blogs have posts with empty content but still have a featured image, so
            // use the featured image as the content
            return String.format("<p><img class='img.size-full' src='%s' /></p>", mPost.getFeaturedImage());
        } else {
            return "";
        }
    }

    /*
     * returns the full content, including CSS, that will be shown in the WebView for this post
     */
    private String getPostContentForWebView(Context context) {
        if (mPost == null || context == null) {
            return "";
        }

        String content = getPostContent();

        StringBuilder sbHtml = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8' />");

        // title isn't strictly necessary, but source is invalid html5 without one
        sbHtml.append("<title>Reader Post</title>");

        // https://developers.google.com/chrome/mobile/docs/webview/pixelperfect
        sbHtml.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");

        // use "Open Sans" Google font
        sbHtml.append("<link rel='stylesheet' type='text/css' href='http://fonts.googleapis.com/css?family=Open+Sans' />");

        sbHtml.append("<style type='text/css'>")
              .append("  body { font-family: 'Open Sans', sans-serif; margin: 0px; padding: 0px;}")
              .append("  body, p, div { max-width: 100% !important; word-wrap: break-word; }")
              .append("  p, div { line-height: 1.6em; font-size: 1em; }")
              .append("  h1, h2 { line-height: 1.2em; }");

        // make sure long strings don't force the user to scroll horizontally
        sbHtml.append("  body, p, div, a { word-wrap: break-word; }");

        // use a consistent top/bottom margin for paragraphs, with no top margin for the first one
        sbHtml.append(String.format("  p { margin-top: %dpx; margin-bottom: %dpx; }",
                mResourceVars.marginSmall, mResourceVars.marginSmall))
              .append("    p:first-child { margin-top: 0px; }");

        // add border, background color, and padding to pre blocks, and add overflow scrolling
        // so user can scroll the block if it's wider than the display
        sbHtml.append("  pre { overflow-x: scroll;")
              .append("        border: 1px solid ").append(mResourceVars.greyLightStr).append("; ")
              .append("        background-color: ").append(mResourceVars.greyExtraLightStr).append("; ")
              .append("        padding: ").append(mResourceVars.marginSmall).append("px; }");

        // add a left border to blockquotes
        sbHtml.append("  blockquote { margin-left: ").append(mResourceVars.marginSmall).append("px; ")
              .append("               padding-left: ").append(mResourceVars.marginSmall).append("px; ")
              .append("               border-left: 3px solid ").append(mResourceVars.greyLightStr).append("; }");

        // show links in the same color they are elsewhere in the app
        sbHtml.append("  a { text-decoration: none; color: ").append(mResourceVars.linkColorStr).append("; }");

        // if javascript is allowed, make sure embedded videos fit the browser width and
        // use 16:9 ratio (YouTube standard) - if not allowed, hide iframes/embeds
        if (canEnableJavaScript()) {
            int videoWidth = DisplayUtils.pxToDp(context,
                    mResourceVars.fullSizeImageWidth - (mResourceVars.marginLarge * 2));
            int videoHeight = (int) (videoWidth * 0.5625f);
            sbHtml.append("  iframe, embed { width: ").append(videoWidth).append("px !important;")
                  .append("                  height: ").append(videoHeight).append("px !important; }");
        } else {
            sbHtml.append("  iframe, embed { display: none; }");
        }

        // don't allow any image to be wider than the screen
        sbHtml.append("  img { max-width: 100% !important; height: auto;}");

        // show large wp images full-width (unnecessary in most cases since they'll already be at least
        // as wide as the display, except maybe when viewed on a large landscape tablet)
        sbHtml.append("  img.size-full, img.size-large { display: block; width: 100% !important; height: auto; }");

        // center medium-sized wp image
        sbHtml.append("  img.size-medium { display: block; margin-left: auto !important; margin-right: auto !important; }");

        // tiled image galleries look bad on mobile due to their hard-coded DIV and IMG sizes, so if
        // content contains a tiled image gallery, remove the height params and replace the width
        // params with ones that make images fit the width of the listView item, then adjust the
        // relevant CSS classes so their height/width are auto, and add top/bottom margin to images
        if (content.contains("tiled-gallery-item")) {
            String widthParam = "w=" + Integer.toString(mResourceVars.fullSizeImageWidth);
            content = content.replaceAll("w=[0-9]+", widthParam).replaceAll("h=[0-9]+", "");
            sbHtml.append("  div.gallery-row, div.gallery-group { width: auto !important; height: auto !important; }")
                  .append("  div.tiled-gallery-item img { ")
                  .append("     width: auto !important; height: auto !important;")
                  .append("     margin-top: ").append(mResourceVars.marginExtraSmall).append("px; ")
                  .append("     margin-bottom: ").append(mResourceVars.marginExtraSmall).append("px; ")
                  .append("  }")
                  .append("  div.tiled-gallery-caption { clear: both; }");
        }

        sbHtml.append("</style></head><body>")
              .append(content)
              .append("</body></html>");

        return sbHtml.toString();
    }

    /*
     *  called when the post doesn't exist in local db, need to get it from server
     */
    private void requestPost() {
        final ProgressBar progress = (ProgressBar) getView().findViewById(R.id.progress_loading);
        progress.setVisibility(View.VISIBLE);
        progress.bringToFront();

        ReaderActions.ActionListener actionListener = new ReaderActions.ActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                if (isAdded()) {
                    progress.setVisibility(View.GONE);
                    if (succeeded) {
                        showPost();
                    } else {
                        postFailed();
                    }
                }
            }
        };
        ReaderPostActions.requestPost(mBlogId, mPostId, actionListener);
    }

    /*
     * called when post couldn't be loaded and failed to be returned from server
     */
    private void postFailed() {
        if (isAdded()) {
            ToastUtils.showToast(getActivity(), R.string.reader_toast_err_get_post, ToastUtils.Duration.LONG);
        }
    }

    /*
     * javascript should only be enabled for wp blogs (not external feeds)
     */
    private boolean canEnableJavaScript() {
        return (mPost != null && mPost.isWP());
    }

    private void showPost() {
        if (mIsPostTaskRunning) {
            AppLog.w(T.READER, "reader post detail > show post task already running");
        }

        new ShowPostTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /*
     * AsyncTask to retrieve this post from SQLite and display it
     */
    private boolean mIsPostTaskRunning = false;

    private class ShowPostTask extends AsyncTask<Void, Void, Boolean> {
        TextView txtTitle;
        TextView txtBlogName;
        TextView txtDateAndAuthor;
        TextView txtFollow;

        ImageView imgBtnReblog;
        ImageView imgBtnLike;
        ImageView imgBtnComment;
        ImageView imgDropDown;
        WPNetworkImageView imgAvatar;
        ViewGroup layoutDetailHeader;

        String postHtml;

        @Override
        protected void onPreExecute() {
            mIsPostTaskRunning = true;
        }

        @Override
        protected void onCancelled() {
            mIsPostTaskRunning = false;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            final View container = getView();
            if (container == null) {
                return false;
            }

            mPost = ReaderPostTable.getPost(mBlogId, mPostId);
            if (mPost == null) {
                return false;
            }

            txtTitle = (TextView) container.findViewById(R.id.text_title);
            txtBlogName = (TextView) container.findViewById(R.id.text_blog_name);
            txtFollow = (TextView) container.findViewById(R.id.text_follow);
            txtDateAndAuthor = (TextView) container.findViewById(R.id.text_date_and_author);

            imgAvatar = (WPNetworkImageView) container.findViewById(R.id.image_avatar);
            imgDropDown = (ImageView) container.findViewById(R.id.image_dropdown);

            imgBtnReblog = (ImageView) mLayoutIcons.findViewById(R.id.image_reblog_btn);
            imgBtnLike = (ImageView) getView().findViewById(R.id.image_like_btn);
            imgBtnComment = (ImageView) mLayoutIcons.findViewById(R.id.image_comment_btn);

            layoutDetailHeader = (ViewGroup) container.findViewById(R.id.layout_detail_header);

            postHtml = getPostContentForWebView(container.getContext());

            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            mIsPostTaskRunning = false;

            if (!isAdded()) {
                return;
            }

            if (!result) {
                // post couldn't be loaded which means it doesn't exist in db, so request it from
                // the server if it hasn't already been requested
                if (!mHasAlreadyRequestedPost) {
                    mHasAlreadyRequestedPost = true;
                    requestPost();
                }
                return;
            }

            // enable JavaScript in the webView if it's safe to do so
            mReaderWebView.getSettings().setJavaScriptEnabled(canEnableJavaScript());

            // IMPORTANT: use loadDataWithBaseURL() since loadData() may fail
            // https://code.google.com/p/android/issues/detail?id=4401
            mReaderWebView.loadDataWithBaseURL(null, postHtml, "text/html", "UTF-8", null);

            txtTitle.setText(mPost.hasTitle() ? mPost.getTitle() : getString(R.string.reader_untitled_post));

            ReaderUtils.showFollowStatus(txtFollow, mPost.isFollowedByCurrentUser);
            txtFollow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    togglePostFollowed(mPost, txtFollow);
                }
            });

            if (mPost.hasBlogName()) {
                txtBlogName.setText(mPost.getBlogName());
                txtBlogName.setVisibility(View.VISIBLE);
            } else if (mPost.hasBlogUrl()) {
                txtBlogName.setText(UrlUtils.getDomainFromUrl(mPost.getBlogUrl()));
                txtBlogName.setVisibility(View.VISIBLE);
            } else {
                txtBlogName.setVisibility(View.GONE);
            }

            // show date and author name if author name exists and is different than the blog name,
            // otherwise just show the date
            if (mPost.hasAuthorName() && !mPost.getAuthorName().equals(mPost.getBlogName())) {
                txtDateAndAuthor.setText(DateTimeUtils.javaDateToTimeSpan(
                        mPost.getDatePublished()) + " / " + mPost.getAuthorName());
            } else {
                txtDateAndAuthor.setText(DateTimeUtils.javaDateToTimeSpan(mPost.getDatePublished()));
            }

            if (mPost.hasPostAvatar()) {
                imgAvatar.setImageUrl(mPost.getPostAvatarForDisplay(
                        mResourceVars.likeAvatarSize), WPNetworkImageView.ImageType.AVATAR);
                imgAvatar.setVisibility(View.VISIBLE);
            } else {
                imgAvatar.setVisibility(View.GONE);
            }

            // hide blog name, avatar & follow button if this fragment was shown from blog preview
            if (isBlogPreview()) {
                layoutDetailHeader.setVisibility(View.GONE);
            }

            // enable reblogging wp posts
            if (mPost.canReblog()) {
                imgBtnReblog.setVisibility(View.VISIBLE);
                imgBtnReblog.setSelected(mPost.isRebloggedByCurrentUser);
                imgBtnReblog.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        reblogPost();
                    }
                });
            } else {
                imgBtnReblog.setVisibility(View.GONE);
            }

            // enable adding a comment if comments are open on this post
            if (mPost.isWP() && mPost.isCommentsOpen) {
                imgBtnComment.setVisibility(View.VISIBLE);
                imgBtnComment.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleShowAddCommentBox();
                    }
                });
            } else {
                imgBtnComment.setVisibility(View.GONE);
            }

            if (mPost.isLikesEnabled) {
                imgBtnLike.setVisibility(View.VISIBLE);
                // if we know refreshLikes() is going to show the liking layout, force it to take up
                // space right now
                if (mPost.numLikes > 0 && mLayoutLikes.getVisibility() == View.GONE) {
                    mLayoutLikes.setVisibility(View.INVISIBLE);
                }
            } else {
                imgBtnLike.setVisibility(View.GONE);
            }

            // external blogs (feeds) don't support action icons
            if (!mPost.isExternal && (mPost.isLikesEnabled
                                   || mPost.canReblog()
                                   || mPost.isCommentsOpen)) {
                mLayoutIcons.setVisibility(View.VISIBLE);
            } else {
                mLayoutIcons.setVisibility(View.GONE);
            }

            // enable blocking the associated blog
            if (canBlockBlog()) {
                imgDropDown.setVisibility(View.VISIBLE);
                imgDropDown.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (mOnPopupListener != null) {
                            mOnPopupListener.onShowPostPopup(view, mPost);
                        }
                    }
                });
            } else {
                imgDropDown.setVisibility(View.GONE);
            }

            // only show action buttons for WP posts
            mLayoutIcons.setVisibility(mPost.isWP() ? View.VISIBLE : View.GONE);

            // make sure the adapter is assigned
            if (getListView().getAdapter() == null) {
                getListView().setAdapter(getCommentAdapter());
            }
        }
    }


    /*
     * called by the web view when the content finishes loading - likes & comments aren't displayed
     * until this is triggered, to avoid having them appear before the webView content
     */
    @Override
    public void onPageFinished(WebView view, String url) {
        if (!isAdded()) {
            return;
        }

        if (url != null && url.equals("about:blank")) {
            // brief delay before loading comments & likes to give a little time for page to render
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isAdded()) {
                        return;
                    }

                    refreshLikes();
                    refreshComments();

                    // request the latest info for this post if we haven't updated it already
                    if (!mHasAlreadyUpdatedPost) {
                        mHasAlreadyUpdatedPost = true;
                        updatePost();
                    }
                }
            }, 500);
        } else {
            AppLog.w(T.READER, "reader post detail > page finished - " + url);
        }
    }

    /*
     * return the container view that should host the fullscreen video
     */
    @Override
    public ViewGroup onRequestCustomView() {
        if (isAdded()) {
            return (ViewGroup) getView().findViewById(R.id.layout_custom_view_container);
        } else {
            return null;
        }
    }

    /*
     * return the container view that should be hidden when fullscreen video is shown
     */
    @Override
    public ViewGroup onRequestContentView() {
        if (isAdded()) {
            return (ViewGroup) getView().findViewById(R.id.layout_post_detail_container);
        } else {
            return null;
        }
    }

    @Override
    public void onCustomViewShown() {
        // fullscreen video has just been shown so hide the ActionBar
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
    }

    @Override
    public void onCustomViewHidden() {
        // user returned from fullscreen video so re-display the ActionBar
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.show();
        }
    }

    boolean isCustomViewShowing() {
        return mReaderWebView != null && mReaderWebView.isCustomViewShowing();
    }

    void hideCustomView() {
        if (mReaderWebView != null) {
            mReaderWebView.hideCustomView();
        }
    }

    @Override
    public boolean onUrlClick(String url) {
        // open YouTube videos in external app so they launch the YouTube player, open all other
        // urls using an AuthenticatedWebViewActivity
        final OpenUrlType openUrlType;
        if (ReaderVideoUtils.isYouTubeVideoLink(url)) {
            openUrlType = OpenUrlType.EXTERNAL;
        } else {
            openUrlType = OpenUrlType.INTERNAL;
        }
        ReaderActivityLauncher.openUrl(getActivity(), url, openUrlType);
        return true;
    }

    @Override
    public boolean onImageUrlClick(String imageUrl, View view, int x, int y) {
        return showPhotoViewer(imageUrl, view, x, y);
    }

    private ActionBar getActionBar() {
        if (isAdded()) {
            return getActivity().getActionBar();
        } else {
            AppLog.w(T.READER, "reader post detail > getActionBar called with no activity");
            return null;
        }
    }

    void pauseWebView() {
        if (mReaderWebView != null) {
            mReaderWebView.hideCustomView();
            mReaderWebView.onPause();
        } else {
            AppLog.i(T.READER, "reader post detail > attempt to pause webView when null");
        }
    }

}