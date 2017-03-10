package com.quickblox.q_municate.ui.fragments.chats;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.quickblox.chat.QBChatService;
import com.quickblox.chat.model.QBChatDialog ;
import com.quickblox.chat.model.QBDialogType;
import com.quickblox.core.exception.QBResponseException;
import com.quickblox.q_municate.R;
import com.quickblox.q_municate.ui.activities.about.AboutActivity;
import com.quickblox.q_municate.ui.activities.chats.NewMessageActivity;
import com.quickblox.q_municate_core.core.command.Command;
import com.quickblox.q_municate_core.core.loader.BaseLoader;
import com.quickblox.q_municate.ui.activities.chats.GroupDialogActivity;
import com.quickblox.q_municate.ui.activities.chats.PrivateDialogActivity;
import com.quickblox.q_municate.ui.activities.feedback.FeedbackActivity;
import com.quickblox.q_municate.ui.activities.invitefriends.InviteFriendsActivity;
import com.quickblox.q_municate.ui.activities.settings.SettingsActivity;
import com.quickblox.q_municate.ui.adapters.chats.DialogsListAdapter;
import com.quickblox.q_municate.ui.fragments.base.BaseLoaderFragment;
import com.quickblox.q_municate.ui.fragments.search.SearchFragment;
import com.quickblox.q_municate.utils.ToastUtils;
import com.quickblox.q_municate_core.models.AppSession;
import com.quickblox.q_municate_core.models.DialogWrapper;
import com.quickblox.q_municate_core.qb.commands.chat.QBDeleteChatCommand;
import com.quickblox.q_municate_core.qb.helpers.QBChatHelper;
import com.quickblox.q_municate_core.service.QBService;
import com.quickblox.q_municate_core.service.QBServiceConsts;
import com.quickblox.q_municate_core.utils.ChatUtils;
import com.quickblox.q_municate_core.utils.UserFriendUtils;
import com.quickblox.q_municate_db.managers.DataManager;
import com.quickblox.q_municate_db.models.DialogNotification;
import com.quickblox.q_municate_db.models.DialogOccupant;
import com.quickblox.q_municate_db.utils.ErrorUtils;
import com.quickblox.q_municate_user_cache.QMUserCacheImpl;
import com.quickblox.q_municate_user_service.QMUserService;
import com.quickblox.q_municate_user_service.model.QMUser;
import com.quickblox.users.model.QBUser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

import butterknife.Bind;
import butterknife.OnClick;
import butterknife.OnItemClick;

public class DialogsListFragment extends BaseLoaderFragment<List<DialogWrapper>> {

    private static final String TAG = DialogsListFragment.class.getSimpleName();
    private static final int LOADER_ID = DialogsListFragment.class.hashCode();

    @Bind(R.id.chats_listview)
    ListView dialogsListView;

    @Bind(R.id.empty_list_textview)
    TextView emptyListTextView;

    private DialogsListAdapter dialogsListAdapter;
    private DataManager dataManager;
    private QBUser qbUser;
    private Observer commonObserver;

    public static DialogsListFragment newInstance() {
        return new DialogsListFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        View view = inflater.inflate(R.layout.fragment_dialogs_list, container, false);

        activateButterKnife(view);

        initFields();
        initChatsDialogs();

        registerForContextMenu(dialogsListView);

        return view;
    }

    @Override
    public void initActionBar() {
        super.initActionBar();
        actionBarBridge.setActionBarUpButtonEnabled(false);

        loadingBridge.hideActionBarProgress();
    }

    private void initFields() {
        dataManager = DataManager.getInstance();
        commonObserver = new CommonObserver();
        qbUser = AppSession.getSession().getUser();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated");
        baseActivity.showSnackbar(R.string.dialog_loading_dialogs, Snackbar.LENGTH_INDEFINITE);
        initDataLoader(LOADER_ID);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.dialogs_list_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_search:
                launchContactsFragment();
                break;
            case R.id.action_start_invite_friends:
                InviteFriendsActivity.start(getActivity());
                break;
            case R.id.action_start_feedback:
                FeedbackActivity.start(getActivity());
                break;
            case R.id.action_start_settings:
                SettingsActivity.start(getActivity());
                break;
            case R.id.action_start_about:
                AboutActivity.start(getActivity());
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, view, menuInfo);
        MenuInflater menuInflater = baseActivity.getMenuInflater();
        AdapterView.AdapterContextMenuInfo adapterContextMenuInfo = (AdapterView.AdapterContextMenuInfo) menuInfo;
        QBChatDialog chatDialog = dialogsListAdapter.getItem(adapterContextMenuInfo.position).getChatDialog();
        if(chatDialog.getType().equals(QBDialogType.GROUP)){
            menuInflater.inflate(R.menu.dialogs_list_group_ctx_menu, menu);
        } else{
            menuInflater.inflate(R.menu.dialogs_list_private_ctx_menu, menu);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo adapterContextMenuInfo = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        switch (item.getItemId()) {
            case R.id.action_delete:
                if (baseActivity.checkNetworkAvailableWithError()) {
                    QBChatDialog chatDialog = dialogsListAdapter.getItem(adapterContextMenuInfo.position).getChatDialog();
                    deleteDialog(chatDialog);
                }
                break;
        }
        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
    }


    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Log.d(TAG, "onActivityCreated");
        addActions();
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "onStart()");
    }

    @Override
    public void onResume() {
        super.onResume();
        addObservers();

        if (dialogsListAdapter != null) {
            checkVisibilityEmptyLabel();
        }

        if (dialogsListAdapter != null) {
            dialogsListAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeActions();
        deleteObservers();
    }

    @OnItemClick(R.id.chats_listview)
    void startChat(int position) {
        QBChatDialog chatDialog = dialogsListAdapter.getItem(position).getChatDialog();

        if (!baseActivity.checkNetworkAvailableWithError() && isFirstOpeningDialog(chatDialog.getDialogId())) {
            return;
        }

        if (QBDialogType.PRIVATE.equals(chatDialog.getType())) {
            startPrivateChatActivity(chatDialog);
        } else {
            startGroupChatActivity(chatDialog);
        }
    }

    @OnClick(R.id.fab_dialogs_new_chat)
    public void onAddChatClick(View view) {
        addChat();
    }

    private boolean isFirstOpeningDialog(String dialogId){
        return !dataManager.getMessageDataManager().getTempMessagesByDialogId(dialogId).isEmpty();
    }

    @Override
    public void onConnectedToService(QBService service) {
        if (chatHelper == null) {
            if (service != null) {
                chatHelper = (QBChatHelper) service.getHelper(QBService.CHAT_HELPER);
            }
        }
    }

    @Override
    protected Loader<List<DialogWrapper>> createDataLoader() {
        return new DialogsListLoader(getActivity(), dataManager);
    }

    @Override
    public void onLoadFinished(Loader<List<DialogWrapper>> loader, List<DialogWrapper> dialogsList) {
        dialogsListAdapter.setNewData(dialogsList);
        checkEmptyList(dialogsList.size());
        if(!baseActivity.isDialogLoading()) {
            baseActivity.hideSnackBar();
        }
    }

    private void addChat(){
        boolean isFriends = !dataManager.getFriendDataManager().getAll().isEmpty();
        if (isFriends) {
            NewMessageActivity.start(getActivity());
        } else {
            ToastUtils.longToast(R.string.new_message_no_friends_for_new_message);
        }
    }

    private void checkVisibilityEmptyLabel() {
        emptyListTextView.setVisibility(dialogsListAdapter.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addObservers() {
        dataManager.getQBChatDialogDataManager().addObserver(commonObserver);
        dataManager.getMessageDataManager().addObserver(commonObserver);
        ((Observable)QMUserService.getInstance().getUserCache()).addObserver(commonObserver);
        dataManager.getDialogOccupantDataManager().addObserver(commonObserver);
    }

    private void deleteObservers() {
        if (dataManager != null) {
            dataManager.getQBChatDialogDataManager().deleteObserver(commonObserver);
            dataManager.getMessageDataManager().deleteObserver(commonObserver);
            ((Observable)QMUserService.getInstance().getUserCache()).deleteObserver(commonObserver);
            dataManager.getDialogOccupantDataManager().deleteObserver(commonObserver);
        }
    }

    private void removeActions() {
        baseActivity.removeAction(QBServiceConsts.DELETE_DIALOG_SUCCESS_ACTION);
        baseActivity.removeAction(QBServiceConsts.DELETE_DIALOG_FAIL_ACTION);


        baseActivity.updateBroadcastActionList();
    }

    private void addActions() {
        baseActivity.addAction(QBServiceConsts.DELETE_DIALOG_SUCCESS_ACTION, new DeleteDialogSuccessAction());
        baseActivity.addAction(QBServiceConsts.DELETE_DIALOG_FAIL_ACTION, new DeleteDialogFailAction());

        baseActivity.updateBroadcastActionList();
    }

    private void initChatsDialogs() {
        List<DialogWrapper> dialogsList = Collections.emptyList();
        dialogsListAdapter = new DialogsListAdapter(baseActivity, dialogsList);
        dialogsListView.setAdapter(dialogsListAdapter);
    }

    private void startPrivateChatActivity(QBChatDialog chatDialog) {
        List<DialogOccupant> occupantsList = dataManager.getDialogOccupantDataManager()
                .getDialogOccupantsListByDialogId(chatDialog.getDialogId());
        QMUser opponent = ChatUtils.getOpponentFromPrivateDialog(UserFriendUtils.createLocalUser(qbUser), occupantsList);

        if (!TextUtils.isEmpty(chatDialog.getDialogId())) {
            PrivateDialogActivity.start(baseActivity, opponent, chatDialog);
        }
    }

    private void startGroupChatActivity(QBChatDialog chatDialog) {
        GroupDialogActivity.start(baseActivity, chatDialog);
    }

    private void updateDialogsList() {
        onChangedData();
    }

    private void deleteDialog(QBChatDialog chatDialog) {
        if(chatDialog == null || chatDialog.getDialogId() == null){
            return;
        }

        baseActivity.showProgress();
        QBDeleteChatCommand.start(baseActivity, chatDialog.getDialogId(), chatDialog.getType().getCode());
    }

    private void checkEmptyList(int listSize) {
        if (listSize > 0) {
            emptyListTextView.setVisibility(View.GONE);
        } else {
            emptyListTextView.setVisibility(View.VISIBLE);
        }
    }

    private void launchContactsFragment() {
        baseActivity.setCurrentFragment(SearchFragment.newInstance(), true);
    }

    private class DeleteDialogSuccessAction implements Command {

        @Override
        public void execute(Bundle bundle) {
            baseActivity.hideProgress();
        }
    }

    private class DeleteDialogFailAction implements Command {

        @Override
        public void execute(Bundle bundle) {
            baseActivity.hideProgress();
        }
    }

    private static class DialogsListLoader extends BaseLoader<List<DialogWrapper>> {

        public DialogsListLoader(Context context, DataManager dataManager) {
            super(context, dataManager);
        }

        @Override
        protected List<DialogWrapper> getItems() {
            List<QBChatDialog> chatDialogs = dataManager.getQBChatDialogDataManager().getAllSorted();
            List<DialogWrapper> dialogWrappers = new ArrayList<>(chatDialogs.size());
            for(QBChatDialog chatDialog : chatDialogs){
                dialogWrappers.add(new DialogWrapper(getContext(), dataManager, chatDialog));
            }
            return dialogWrappers;
        }
    }

    private class CommonObserver implements Observer {

        @Override
        public void update(Observable observable, Object data) {
            if (data != null) {
                if (data.equals(dataManager.getQBChatDialogDataManager().getObserverKey())
                        || data.equals(dataManager.getMessageDataManager().getObserverKey())
                        || data.equals(QMUserCacheImpl.OBSERVE_KEY)
                        || data.equals(dataManager.getDialogOccupantDataManager().getObserverKey())) {
                    updateDialogsList();
                }
            }
        }
    }
}