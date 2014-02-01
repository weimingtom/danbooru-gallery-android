////////////////////////////////////////////////////////////////////////////////
// Danbooru Gallery Android - an danbooru-style imageboard browser
//     Copyright (C) 2014  Victor Tseng
//
//     This program is free software: you can redistribute it and/or modify
//     it under the terms of the GNU General Public License as published by
//     the Free Software Foundation, either version 3 of the License, or
//     (at your option) any later version.
//
//     This program is distributed in the hope that it will be useful,
//     but WITHOUT ANY WARRANTY; without even the implied warranty of
//     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//     GNU General Public License for more details.
//
//     You should have received a copy of the GNU General Public License
//     along with this program. If not, see <http://www.gnu.org/licenses/>
////////////////////////////////////////////////////////////////////////////////

package tw.idv.palatis.danboorugallery.util;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.os.CancellationSignal;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import tw.idv.palatis.danboorugallery.DanbooruGallerySettings;
import tw.idv.palatis.danboorugallery.database.HostsTable;
import tw.idv.palatis.danboorugallery.database.PostsTable;
import tw.idv.palatis.danboorugallery.model.Host;
import tw.idv.palatis.danboorugallery.model.Post;
import tw.idv.palatis.danboorugallery.model.Tag;
import tw.idv.palatis.danboorugallery.siteapi.SiteAPI;

/**
 * This is a singleton class manage the connections and data from hosts.
 *
 */
public class SiteSession
{
    private static final String TAG = "SiteSession";

    private static final ReentrantReadWriteLock sHostsLock = new ReentrantReadWriteLock();
    private static final List<Host> sHosts = new ArrayList<>();
    private static SharedPreferences.OnSharedPreferenceChangeListener sOnSharedPreferenceChangeListener;

    public static void init()
    {
        // populate the hosts the first time.
        new Thread() {
            @Override
            public void run()
            {
                HostsTable.registerDataSetObserver(new DataSetObserver() {
                    @Override
                    public void onChanged()
                    {
                        super.onChanged();
                        new Thread()
                        {
                            @Override
                            public void run()
                            {
                                rebuildHosts();
                                rebuildTempTable();
                                fetchPosts(0, null);
                            }
                        }.start();
                    }

                    @Override
                    public void onInvalidated()
                    {
                        super.onInvalidated();
                        Lock lock = sHostsLock.writeLock();
                        lock.lock();
                        sHosts.clear();
                        lock.unlock();
                    }
                }, 100);

                sOnSharedPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener()
                {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
                    {
                        if (key.equals(DanbooruGallerySettings.KEY_PREF_FILTER_WIDTH) ||
                            key.equals(DanbooruGallerySettings.KEY_PREF_FILTER_HEIGHT) ||
                            key.equals(DanbooruGallerySettings.KEY_PREF_FILTER_RATING_SAFE) ||
                            key.equals(DanbooruGallerySettings.KEY_PREF_FILTER_RATING_QUESTIONABLE) ||
                            key.equals(DanbooruGallerySettings.KEY_PREF_FILTER_RATING_EXPLICIT))
                        {
                            rebuildFilterQuery();
                            new Thread()
                            {
                                @Override
                                public void run()
                                {
                                    rebuildTempTable();
                                    fetchPosts(0, null);
                                }
                            }.start();
                        }
                    }
                };
                DanbooruGallerySettings.registerOnSharedPreferenceChangeListener(sOnSharedPreferenceChangeListener);

                rebuildFilterQuery();
                rebuildHosts();
                rebuildTempTable();
                fetchPosts(0, null);
            }
        }.start();
    }

    private static void rebuildTempTable()
    {
        Lock lock = sHostsLock.readLock();
        lock.lock();
        List<Host> hosts = new ArrayList<>(sHosts);
        lock.unlock();

        lock = sFilterTagsLock.readLock();
        lock.lock();
        String filterTags = sFilterTags;
        lock.unlock();

        PostsTable.rebuildTempTable(hosts, filterTags);
    }

    private static void rebuildHosts()
    {
        Lock lock = sHostsLock.writeLock();
        lock.lock();
        sHosts.clear();
        Cursor cursor = HostsTable.getAllHostsCursor();
        while (cursor.moveToNext())
            sHosts.add(Host.getFromCursor(cursor));
        lock.unlock();
    }

    private static final ReentrantReadWriteLock sSelectionsLock = new ReentrantReadWriteLock();
    private static String sSelectionString = "";
    private static String[] sSelectionArgs = new String[0];
    synchronized private static void rebuildFilterQuery()
    {
        int width = DanbooruGallerySettings.getFilterImageWidth();
        int height = DanbooruGallerySettings.getFilterImageHeight();
        boolean s = DanbooruGallerySettings.getFilterRatingSafe();
        boolean q = DanbooruGallerySettings.getFilterRatingQuestionable();
        boolean e = DanbooruGallerySettings.getFilterRatingExplicit();

        StringBuilder sb = new StringBuilder();
        List<String> args = new ArrayList<>();

        if (width > 0)
        {
            sb.append(Post.KEY_POST_IMAGE_WIDTH).append(" >= ? ");
            args.add(Integer.toString(width));
        }
        else if (width < 0)
        {
            sb.append(Post.KEY_POST_IMAGE_WIDTH).append(" <= ? ");
            args.add(Integer.toString(Math.abs(width)));
        }

        if (height > 0)
        {
            if (sb.length() != 0)
                sb.append("AND ");

            sb.append(Post.KEY_POST_IMAGE_HEIGHT).append(" >= ? ");
            args.add(Integer.toString(height));
        }
        else if (height < 0)
        {
            if (sb.length() != 0)
                sb.append("AND ");

            sb.append(Post.KEY_POST_IMAGE_HEIGHT).append(" <= ? ");
            args.add(Integer.toString(Math.abs(height)));
        }

        if (sb.length() != 0)
            sb.append("AND ");

        sb.append(Post.KEY_POST_RATING).append(" IN ( ");

        if (s)
        {
            sb.append("?,");
            args.add("s");
        }

        if (q)
        {
            sb.append("?,");
            args.add("q");
        }

        if (e)
        {
            sb.append("?,");
            args.add("e");
        }

        sb.setLength(sb.length() - 1);
        sb.append(")");

        Lock lock = sSelectionsLock.writeLock();
        lock.lock();
        sSelectionString = sb.toString();
        sSelectionArgs = new String[args.size()];
        args.toArray(sSelectionArgs);
        lock.unlock();
    }

    private static String sTagSearchPattern = "";

    /**
     * set the tag search match pattern
     * @param match_pattern the new match pattern
     * @return              true if the new match pattern is the same as the old one,
     *                      false if different.
     */
    synchronized public static boolean setTagSearchPattern(String match_pattern)
    {
        if (match_pattern.equals(sTagSearchPattern))
            return true;
        sTagSearchPattern = match_pattern;
        return false;
    }

    public static String getTagSearchPattern()
    {
        return sTagSearchPattern;
    }

    // runs on a worker thread
    private static List<Tag> sEmptyTags = new ArrayList<>();
    public static Cursor searchTags(CancellationSignal signal)
    {
        String[] patterns = TextUtils.split(sTagSearchPattern, " ");
        String pattern;
        if (patterns.length > 0)
            pattern = patterns[patterns.length - 1];
        else
            pattern = "";

        if (signal.isCanceled())
            return new TagCursor(sEmptyTags);

        Lock lock = sHostsLock.readLock();
        lock.lock();
        List<Host> hosts = new ArrayList<>(sHosts);
        lock.unlock();
        if (signal.isCanceled())
            return new TagCursor(sEmptyTags);

        SparseArray<Tag> allTags = new SparseArray<>();
        for (Host host : hosts)
        {
            if (!host.enabled)
                continue;

            if (signal.isCanceled())
                break;

            try
            {
                List<Tag> tags = host.getAPI().searchTags(host, pattern);
                for (Tag tag : tags)
                {
                    if (signal.isCanceled())
                        break;

                    Tag oldTag = allTags.get(tag.name.hashCode());
                    if (oldTag != null)
                    {
                        oldTag.post_count += tag.post_count;
                        oldTag.hosts.add(host);
                    }
                    else
                    {
                        tag.hosts.add(host);
                        allTags.put(tag.name.hashCode(), tag);
                    }
                }
            }
            catch (SiteAPI.SiteAPIException ex)
            {
                Log.d(TAG, "SiteAPI thrown an exception.", ex);
            }
        }
        List<Tag> tags = new ArrayList<>(allTags.size());
        for (int i = allTags.size() - 1;i >= 0;--i)
            tags.add(allTags.valueAt(i));

        // TODO: support different comparators
        Collections.sort(tags, new Comparator<Tag>()
        {
            @Override
            public int compare(Tag lhs, Tag rhs)
            {
                return rhs.post_count - lhs.post_count;
            }
        });

        return new TagCursor(tags);
    }

    private static ReentrantReadWriteLock sFilterTagsLock = new ReentrantReadWriteLock();
    private static String sFilterTags = "";

    public static String getFilterTags()
    {
        Lock lock = sFilterTagsLock.readLock();
        lock.lock();
        String filterTags = sFilterTags;
        lock.unlock();
        return filterTags;
    }

    private static OnlyNewestSingleThreadExecutor sRebuildTempPostsTableExecutor = new OnlyNewestSingleThreadExecutor();
    private static Runnable sRebuildTempPostsTableRunnable =
        new Runnable() {
            @Override
            public void run()
            {
                Lock lock = sHostsLock.readLock();
                lock.lock();
                List<Host> hosts = new ArrayList<>(sHosts);
                lock.unlock();

                lock = sFilterTagsLock.readLock();
                lock.lock();
                String filterTags = sFilterTags;
                lock.unlock();

                PostsTable.rebuildTempTable(hosts, filterTags);
            }
        };


    public static void submitFilterTags(String tags)
    {
        Lock lock = sFilterTagsLock.writeLock();
        lock.lock();
        sFilterTags = tags;
        lock.unlock();
        sRebuildTempPostsTableExecutor.execute(sRebuildTempPostsTableRunnable);
    }

    public static Cursor getAllPostsCursor(String[] columns)
    {
        Lock lock = sSelectionsLock.readLock();
        lock.lock();
        String selection = sSelectionString;
        String[] selectionArgs = new String[sSelectionArgs.length];
        System.arraycopy(sSelectionArgs, 0, selectionArgs, 0, sSelectionArgs.length);
        lock.unlock();
        return PostsTable.getTempPostsCursor(columns, selection, selectionArgs, Post.KEY_POST_CREATED_AT + " DESC", null);
    }

    public static Host getHostById(int id)
    {
        Lock lock = sHostsLock.readLock();
        lock.lock();
        for (Host host : sHosts)
            if (host.id == id)
            {
                lock.unlock();
                return host;
            }
        lock.unlock();
        return null;
    }

    private static OnlyNewestSingleThreadExecutor sFetchPostExecutor = new OnlyNewestSingleThreadExecutor();
    private static class FetchPostRunnable
        implements Runnable
    {
        private final Runnable mPreExecuteRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                mCallback.onPreExecute();
            }
        };

        private final Runnable mPostExecuteRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                mCallback.onPostExecute();
            }
        };

        private final Runnable mProgressUpdateRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                mCallback.onProgressUpdate(mProgress);
            }
        };

        private final Runnable mOnErrorRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                mCallback.onError(mErrorThrowable);
            }
        };

        private long mPostCreatedAt;
        private int mProgress = 0;
        private SiteAPI.SiteAPIException mErrorThrowable;
        private LoadingCallback mCallback;

        public FetchPostRunnable(long post_created_at, LoadingCallback callback)
        {
            mPostCreatedAt = post_created_at;
            if (callback == null)
                mCallback = sDummyLoadingCallback;
            else
                mCallback = callback;
        }

        // TODO: supports CancellationSignal
        @Override
        public void run()
        {
            sHandler.post(mPreExecuteRunnable);

            Lock lock = sHostsLock.readLock();
            lock.lock();
            List<Host> hosts = new ArrayList<>(sHosts);
            lock.unlock();

            lock = sFilterTagsLock.readLock();
            lock.lock();
            String filterTags = sFilterTags;
            lock.unlock();

            for (Host host : hosts)
            {
                if (!host.enabled)
                    continue;

                SiteAPI api = host.getAPI();

                try
                {
                    int position = PostsTable.getPostPosition(host, mPostCreatedAt);

                    List<Post> posts = api.fetchPosts(host, position, filterTags);

                    // fetch the next page to avoid stall
                    int updated = PostsTable.addOrUpdatePosts(host, posts);
                    int limit = host.getPageLimit(DanbooruGallerySettings.getBandwidthUsageType());
                    if (updated == limit)
                    {
                        posts = api.fetchPosts(host, position + limit, filterTags);
                        PostsTable.addOrUpdatePosts(host, posts);
                    }
                    PostsTable.rebuildTempTable(hosts, filterTags);

                    mProgress += posts.size();

                    sHandler.post(mProgressUpdateRunnable);
                }
                catch (SiteAPI.SiteAPIException ex)
                {
                    mErrorThrowable = ex;
                    sHandler.post(mOnErrorRunnable);
                }
            }

            sHandler.post(mPostExecuteRunnable);
        }
    }

    private static Handler sHandler = new Handler();

    // must be called from the UI thread
    public static void fetchPosts(long post_created_at, LoadingCallback callback)
    {
        sFetchPostExecutor.execute(new FetchPostRunnable(post_created_at, callback));
    }

    public static interface LoadingCallback
    {
        public void onPreExecute();
        public void onProgressUpdate(int progress);
        public void onPostExecute();
        public void onError(SiteAPI.SiteAPIException error);
    }

    private static final LoadingCallback sDummyLoadingCallback = new LoadingCallback() {
        @Override
        public void onPreExecute() { }
        @Override
        public void onProgressUpdate(int progress) { }
        @Override
        public void onPostExecute() { }
        @Override
        public void onError(SiteAPI.SiteAPIException error) { }
    };
}